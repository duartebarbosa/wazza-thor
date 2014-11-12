package wazza.thor.jobs

import com.mongodb.BasicDBObject
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.typesafe.config.{Config, ConfigFactory}
import java.text.SimpleDateFormat
import java.util.Date
import org.apache.spark._
import org.apache.hadoop.conf.Configuration
import org.bson.BSONObject
import org.bson.BasicBSONObject
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.hadoop.conf.Configuration
import scala.concurrent._
import ExecutionContext.Implicits.global
import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ActorRef}
import scala.collection.immutable.StringOps
import wazza.thor.messages._

object TotalRevenue {

  def props(ctx: SparkContext, dependants: List[ActorRef]): Props = Props(new TotalRevenue(ctx, dependants))
}

class TotalRevenue(
  ctx: SparkContext,
  dependants: List[ActorRef]
) extends Actor with ActorLogging with WazzaActor with CoreJob with Serializable {
  import context._

  def inputCollectionType: String = "purchases"
  def outputCollectionType: String = "TotalRevenue"

  private def saveResultToDatabase(
    uriStr: String,
    collectionName: String,
    totalRevenue: Double,
    lowerDate: Date,
    upperDate: Date,
    companyName: String,
    applicationName: String
  ) = {
    val uri  = new MongoClientURI(uriStr)
    val client = new MongoClient(uri)
    val collection = client.getDB(uri.getDatabase()).getCollection(collectionName)
    val result = new BasicDBObject
    result.put("totalRevenue", totalRevenue)
    result.put("lowerDate", lowerDate.getTime)
    result.put("upperDate", upperDate.getTime)
    collection.insert(result)
    client.close()
  }

  private def executeJob(
    inputCollection: String,
    outputCollection: String,
    lowerDate: Date,
    upperDate: Date,
    companyName: String,
    applicationName: String
  ): Future[Unit] = {

    def parseFloat(d: String): Option[Long] = {
      try { Some(d.toLong) } catch { case _: Throwable => None }
    }

    val promise = Promise[Unit]
    val inputUri = s"${ThorContext.URI}.${inputCollection}"
    val outputUri = s"${ThorContext.URI}.${outputCollection}"
    val df = new SimpleDateFormat("yyyy/MM/dd")
    val jobConfig = new Configuration
    jobConfig.set("mongo.input.uri", inputUri)
    jobConfig.set("mongo.output.uri", outputUri)
    jobConfig.set("mongo.input.split.create_input_splits", "false")

    println(jobConfig)
    println(ctx)
    println(inputUri)

    val mongoDf = new SimpleDateFormat("yyyy-MM-dd")
    val mongoRDD = ctx.newAPIHadoopRDD(
      jobConfig,
      classOf[com.mongodb.hadoop.MongoInputFormat],
      classOf[Object],
      classOf[BSONObject]
    )/**.filter(t => {
      parseFloat(t._2.get("time").toString) match {
        case Some(dbDate) => {
          val startDate = new Date(dbDate)
          startDate.compareTo(lowerDate) * upperDate.compareTo(startDate) >= 0
        }
        case _ => {
          log.error(s"ERROR")
          false
        }
      }
    })**/


    if(mongoRDD.count() > 0) {
      val totalRevenue = mongoRDD.map(arg => {
        val price = arg._2.get("price").toString.toDouble
        price
      }).reduce(_ + _)

      saveResultToDatabase(
        ThorContext.URI,
        outputCollection,
        totalRevenue,
        lowerDate,
        upperDate,
        companyName,
        applicationName
      )
      promise.success()
    } else  {
      log.error("Count is zero")
      promise.failure(new Exception)
    }

    promise.future
  }

  def receive = {
    case InitJob(companyName ,applicationName, lowerDate, upperDate) => {
      log.info(s"InitJob received - $companyName | $applicationName | $lowerDate | $upperDate")
      //sender ! JobCompleted("Total Revenue", new Success)
      //stop(self)
      executeJob(
        getCollectionInput(companyName, applicationName),
        getCollectionOutput(companyName, applicationName),
        lowerDate,
        upperDate,
        companyName,
        applicationName
      ) map {res =>
        sender ! JobCompleted("Total Revenue", new Success)
        dependants.foreach{_ ! CoreJobCompleted("Total Revenue", companyName, applicationName)}
        stop(self)
      } recover {
        case ex: Exception => {
          sender ! JobCompleted("Total Revenue", new Failure(ex))
          stop(self)
        }
      }
    }
  }
}
