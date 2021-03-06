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

import com.mongodb.BasicDBObject
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import java.util.Date
import org.apache.spark._
import org.apache.spark.rdd.RDD
import scala.util.Try
import org.apache.hadoop.conf.Configuration
import org.bson.BSONObject
import org.bson.BasicBSONObject
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.hadoop.conf.Configuration
import scala.concurrent._
import akka.actor.{Actor, ActorLogging, Props, ActorRef}
import scala.collection.immutable.StringOps
import wazza.thor.NotificationMessage
import wazza.thor.NotificationsActor
import wazza.thor.messages._

object NumberSessions {

  def props(ctx: SparkContext, d: List[ActorRef]): Props = Props(new NumberSessions(ctx, d))
}

class NumberSessions(
  ctx: SparkContext,
  d: List[ActorRef]
) extends Actor with ActorLogging  with CoreJob {
  import context._

  dependants = d

  def inputCollectionType: String = "mobileSessions"
  def outputCollectionType: String = "numberSessions"

  private def executeJob(
    inputCollection: String,
    outputCollection: String,
    lowerDate: Date,
    upperDate: Date,
    platforms: List[String]
  ): Future[Unit] = {

    val promise = Promise[Unit]
    val uri = ThorContext.URI
    val inputUri = s"${uri}.${inputCollection}"
    val outputUri = s"${uri}.${outputCollection}"
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
    val rdds = getRDDPerPlatforms("startTime", platforms, rdd, lowerDate, upperDate, ctx)

    // Calculates results per platform
    val platformResults = rdds map {rdd =>
      val numberSessions = if(rdd._2.count > 0) {
        rdd._2.map((arg: Tuple2[Object, BSONObject]) => {
          (arg._2.get("id"), 1)
        }).groupByKey.count
      } else 0
      new PlatformResults(rdd._1, numberSessions, None)
    }

    val totalNumberSessions = platformResults.foldLeft(0.0)(_ + _.res)
    val results = new Results(totalNumberSessions, platformResults, lowerDate, upperDate)
    saveResultToDatabase(ThorContext.URI, outputCollection, results)
    promise.success()
    promise.future
  }

  def kill = stop(self)

  def receive = {
    case InitJob(companyName ,applicationName, platforms, paymentSystems, lowerDate, upperDate) => {
      try {
        log.info(s"InitJob received - $companyName | $applicationName | $lowerDate | $upperDate")
        supervisor = sender
        executeJob(
          getCollectionInput(companyName, applicationName),
          getCollectionOutput(companyName, applicationName),
          lowerDate,
          upperDate,
          platforms
        ) map {res =>
          log.info("Job completed successful")
          onJobSuccess(companyName, applicationName, "Number Sessions", lowerDate, upperDate, platforms, paymentSystems)
        } recover {
          case ex: Exception => {
            log.error("Job failed")
            onJobFailure(ex, "Number Sessions")
          }
        }
      } catch {
        case ex: Exception => {
          log.error(ex.getStackTraceString)
          NotificationsActor.getInstance ! new NotificationMessage("SPARK ERROR - NUMBER SESSIONS", ex.getStackTraceString)
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

