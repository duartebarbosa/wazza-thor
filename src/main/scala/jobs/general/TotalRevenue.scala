/*
 * wazza-thor
 * https://github.com/Wazzaio/wazza-thor
 * Copyright (C) 2013-2015  Duarte Barbosa, João Vazão Vasques
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package wazza.thor.jobs

import com.mongodb.casbah.Imports._
import com.typesafe.config.{Config, ConfigFactory}
import java.text.SimpleDateFormat
import java.util.Date
import org.apache.spark._
import org.apache.hadoop.conf.Configuration
import org.apache.spark.rdd.RDD
import org.bson.BSONObject
import org.apache.spark.SparkContext._
import org.apache.hadoop.conf.Configuration
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import scala.collection.mutable.ListBuffer
import scala.concurrent._
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ActorRef}
import scala.collection.immutable.StringOps
import wazza.thor.NotificationMessage
import wazza.thor.NotificationsActor
import wazza.thor.messages._
import akka.actor.PoisonPill

object TotalRevenue {

  def props(ctx: SparkContext, dependants: List[ActorRef]): Props = Props(new TotalRevenue(ctx, dependants))
}

class TotalRevenue(
  ctx: SparkContext,
  d: List[ActorRef]
) extends Actor with ActorLogging with CoreJob  {
  import context._

  dependants = d

  def inputCollectionType: String = "purchases"
  def outputCollectionType: String = "TotalRevenue"

  private def executeJob(
    inputCollection: String,
    outputCollection: String,
    lowerDate: Date,
    upperDate: Date,
    companyName: String,
    applicationName: String,
    platforms: List[String],
    paymentSystems: List[Int]
  ): Future[Unit] = {

    val promise = Promise[Unit]

    // Creates RDD based on configuration
    val inputUri = s"${ThorContext.URI}.${inputCollection}"
    val outputUri = s"${ThorContext.URI}.${outputCollection}"
    val jobConfig = new Configuration
    jobConfig.set("mongo.input.uri", inputUri)
    jobConfig.set("mongo.output.uri", outputUri)
    jobConfig.set("mongo.input.split.create_input_splits", "false")

    val rdd = ctx.newAPIHadoopRDD(
      jobConfig,
      classOf[com.mongodb.hadoop.MongoInputFormat],
      classOf[Object],
      classOf[BSONObject]
    )

    // Creates an RDD with data of mobile platform
    val rdds = getRDDPerPlatforms("time", platforms, rdd, lowerDate, upperDate, ctx)

    // Calculates results per platform
    val platformResults = rdds map {rdd =>
      if(rdd._2.count() > 0) {
        // Filters only the entrances that match the selected payment systems
        val filteredRDD = rdd._2/**.filter{el =>
          paymentSystems.contains(el._2.get("paymentSystem").toString.toInt)
        }**/

        if(filteredRDD.count > 0) {
          val totalRevenuePerPaymentSystem = rdd._2.map(arg => {
            val paymentSystem = arg._2.get("paymentSystem").toString
            val price = arg._2.get("price").toString.toDouble
            (paymentSystem, price)
          }).reduceByKey(_ + _)

          val totalRevenue = totalRevenuePerPaymentSystem.values.reduce(_ + _)
          val paymentSystemResults = totalRevenuePerPaymentSystem.collect.toList map {el =>
            new PaymentSystemResult(el._1.toDouble.toInt, el._2)
          }
          new PlatformResults(rdd._1, totalRevenue, Some(paymentSystemResults))
        } else {
          new PlatformResults(rdd._1, 0.0, Some(paymentSystems map {p => new PaymentSystemResult(p, 0.0)}))
        }
      } else {
        new PlatformResults(rdd._1, 0.0, Some(paymentSystems map {p => new PaymentSystemResult(p, 0.0)}))
      }
    }
      
    // Calculates total results and persist them
    val results = {
      val totalRevenue = platformResults.foldLeft(0.0)(_ + _.res)
      new Results(totalRevenue, platformResults, lowerDate, upperDate)
    }
    saveResultToDatabase(ThorContext.URI, outputCollection, results)
    promise.success()
    promise.future
  }

  def kill = stop(self)

  def receive = {
    case InitJob(companyName ,applicationName, platforms, paymentSystems, lowerDate, upperDate) => {
      log.info(s"InitJob received - $companyName | $applicationName | $lowerDate | $upperDate")
      supervisor = sender
      try {
        executeJob(
          getCollectionInput(companyName, applicationName),
          getCollectionOutput(companyName, applicationName),
          lowerDate,
          upperDate,
          companyName,
          applicationName,
          platforms,
          paymentSystems
        ) map {res =>
          log.info("Job completed successful")
          onJobSuccess(companyName, applicationName, "Total Revenue", lowerDate, upperDate, platforms, paymentSystems)
        } recover {
          case ex: Exception => {
            log.error("Job failed")
            onJobFailure(ex, "Total Revenue")
          }
        }
      } catch {
        case ex: Exception => {
          log.error(ex.getStackTraceString)
          NotificationsActor.getInstance ! new NotificationMessage("SPARK ERROR - TOTAL REVENUE", ex.getStackTraceString)
          onJobFailure(ex, self.path.name)
        }
      }
    }
    /** Must wait for all childs to finish **/
    case JobCompleted(jobType, status) => {
      childJobsCompleted = childJobsCompleted :+ jobType
      if(childJobsCompleted.size == dependants.size) {
        log.info("All child jobs have finished")
        supervisor ! new JobCompleted(self.path.name, new wazza.thor.messages.WZSuccess)
        kill
      }
    }
  }
}
