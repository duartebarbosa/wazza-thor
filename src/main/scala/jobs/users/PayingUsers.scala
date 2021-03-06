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

import akka.event.LoggingAdapter
import com.typesafe.config.{Config, ConfigFactory}
import java.text.SimpleDateFormat
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
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ActorRef}
import scala.util.{Success,Failure}
import scala.collection.JavaConversions._
import java.util.ArrayList
import scala.collection.Iterable
import scala.collection.immutable.StringOps
import wazza.thor.NotificationMessage
import wazza.thor.NotificationsActor
import wazza.thor.messages._
import com.mongodb.casbah.Imports._
import play.api.libs.json._
import scala.collection.mutable._

object PayingUsers {
  
  def props(ctx: SparkContext, d: List[ActorRef]): Props = Props(new PayingUsers(ctx, d))

  def mapRDD(
    rdd: RDD[Tuple2[Object, BSONObject]],
    lowerDate: Date,
    upperDate: Date,
    platforms: List[String],
    paymentSystems: List[Int],
    log: LoggingAdapter
  ): RDD[UserPurchases] = {
    rdd.map(purchases => {
      def parseDate(d: String): Option[Date] = {
        try {
          Some(new Date(d.toDouble.toLong))
        } catch {
          case e: Throwable => {
            //log.error(e.getMessage)
            None
          }
        }
      }

      val time = parseDate(purchases._2.get("time").toString)
      val platform = (Json.parse(purchases._2.get("device").toString) \ "osType").as[String]
      val paymentSystem = purchases._2.get("paymentSystem").toString.toDouble.toInt
      val info = new PurchaseInfo(purchases._2.get("id").toString, time.get, Some(platform), paymentSystem)
      (purchases._2.get("userId").toString, info)
    }).groupByKey.map(purchaseInfo => {
      val userId = purchaseInfo._1
      val purchasesPerPlatform = platforms map {p =>
        val platformPurchases = purchaseInfo._2.filter(pInfo  => {
          val platformPredicate = pInfo.platform match {
            case Some(opt) => opt == p
            case _ => false
          }
          val paymentSystemPredicate = paymentSystems.contains(pInfo.paymentSystem)
          platformPredicate && paymentSystemPredicate
        }) map {pp =>
          new PurchaseInfo(pp.purchaseId, pp.time, None, pp.paymentSystem)
        }
        new PlatformPurchases(p, platformPurchases.toList)
      }

      new UserPurchases(userId, purchaseInfo._2.toList, purchasesPerPlatform, lowerDate, upperDate)
    })
  }
}

case class PlatformPurchases(platform: String, purchases: List[PurchaseInfo])

case class PurchaseInfo(
  purchaseId: String,
  val time: Date,
  platform: Option[String] = None,
  paymentSystem: Int
) extends Ordered[PurchaseInfo] {
  def compare(that: PurchaseInfo): Int = this.time.compareTo(that.time)
}

case class UserPurchases(
  userId: String,
  totalPurchases: List[PurchaseInfo],
  purchasesPerPlatform: List[PlatformPurchases],
  lowerDate: Date, upperDate: Date
)

class PayingUsers(
  ctx: SparkContext,
  d: List[ActorRef]
) extends Actor with ActorLogging  with CoreJob {
  import context._

  dependants = d

  override def inputCollectionType: String = "purchases"
  override def outputCollectionType: String = "payingUsers"

  implicit def userPurchaseToBson(u: UserPurchases): DBObject = {
    val purchasesPerPlatform = if(u.purchasesPerPlatform.isEmpty) {
      List()
    } else 
      u.purchasesPerPlatform map {platformPurchaseToBson(_)}
    
    MongoDBObject(
      "userId" -> u.userId,
      "purchases" -> (u.totalPurchases map {purchaseInfoToBson(_)}),
      "purchasesPerPlatform" -> purchasesPerPlatform,
      "lowerDate" -> u.lowerDate,
      "upperDate" -> u.upperDate
    )
  }

  implicit def platformPurchaseToBson(p: PlatformPurchases): MongoDBObject = {
    MongoDBObject(
      "platform" -> p.platform,
      "purchases" -> (p.purchases map {purchaseInfoToBson(_)})
    )
  }

  implicit def purchaseInfoToBson(p: PurchaseInfo): MongoDBObject = {
    val builder = MongoDBObject.newBuilder
    builder += "purchaseId" -> p.purchaseId
    builder += "time" -> p.time
    builder += "paymentSystem" -> p.paymentSystem
    p.platform match {
      case Some(platform) => builder += "platform" -> platform
      case None => {}
    }
    builder.result
  }

  private def saveResultToDatabase(
    uriStr: String,
    collectionName: String,
    payingUsers: List[UserPurchases],
    lowerDate: Date,
    upperDate: Date
  ): Future[Unit] = {
    val promise = Promise[Unit]
    Future {
      val uri  = MongoClientURI(uriStr)
      val client = MongoClient(uri)
      try {
        val collection = client.getDB(uri.database.get)(collectionName)
        payingUsers foreach {e => collection.insert(userPurchaseToBson(e))}
        client.close
        promise.success()
      } catch {
        case ex: Exception => {
          log.error(ex.getMessage)
          client.close
          promise.failure(ex)
        }
      }
    }
    promise.future
  }

   def executeJob(
     inputCollection: String,
     outputCollection: String,
     lowerDate: Date,
     upperDate: Date,
     platforms: List[String],
     paymentSystems: List[Int]
   ): Future[Unit] = {

     val promise = Promise[Unit]
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
     ).filter(t => {
       def parseDate(d: String): Option[Date] = {
         try {
           Some(new Date(d.toDouble.toLong))
         } catch {
           case e: Throwable => {
             println("error: " + e.getMessage())
             None
           }
         }
       }

       val dateStr = t._2.get("time").toString
       parseDate(dateStr) match {
         case Some(startDate) => {
           startDate.compareTo(lowerDate) * upperDate.compareTo(startDate) >= 0
         }
         case _ => false
       }
     })

     val result = if(rdd.count() > 0) {
       PayingUsers.mapRDD(rdd, lowerDate, upperDate, platforms, paymentSystems, log).collect.toList
     } else {
       List[UserPurchases]()
     }

     val dbResult = saveResultToDatabase(ThorContext.URI,
       outputCollection,
       result,
       lowerDate,
       upperDate
     )

     dbResult onComplete {
       case Success(_) => promise.success()
       case Failure(ex) => promise.failure(ex)
     }
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
          platforms,
          paymentSystems
        ) map {res =>
          log.info("Job completed successful")
          onJobSuccess(companyName, applicationName, self.path.name, lowerDate, upperDate, platforms, paymentSystems)
        } recover {
          case ex: Exception => {
            log.error("Job failed")
            onJobFailure(ex, self.path.name)
          }
        }
      } catch {
        case ex: Exception => {
          log.error(ex.getStackTraceString)
          NotificationsActor.getInstance ! new NotificationMessage("SPARK ERROR - PAYING USERS", ex.getStackTraceString)
          onJobFailure(ex, self.path.name)
        }
      }
    }
    /** Must wait for all childs to finish **/
    case JobCompleted(jobType, status) => {
      childJobsCompleted = jobType :: childJobsCompleted
      if(childJobsCompleted.size == dependants.size) {
        log.info("All child jobs have finished")
        supervisor ! new JobCompleted(self.path.name, new WZSuccess)
        kill
      }
    }
  }
}
