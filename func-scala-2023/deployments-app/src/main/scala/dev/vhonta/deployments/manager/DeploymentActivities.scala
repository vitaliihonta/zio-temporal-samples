package dev.vhonta.deployments.manager

import zio.ZIO.ifZIO
import zio._
import zio.temporal._
import zio.temporal.activity._

import java.net.URL

case class DeployServiceParams(name: String)

case class DeployServiceResult(
  serviceDeploymentId: String)

case class HttpTrafficStatsParams(
  hostname: URL)

sealed trait TrafficStatsResult {
  def error: Option[String]
  def statsType: String

}

case class HttpTrafficStatsResult(
  numRequests: Int,
  numErrors:   Int)
    extends TrafficStatsResult {

  override val statsType: String = "HTTP traffic check"

  override val error: Option[String] = {
    val errorRatio = 100 - (numRequests - numErrors)
    Option.when(errorRatio > 5)("Error ratio > 5%")
  }
}

case class KafkaTrafficStatsParams(
  topic:         String,
  consumerGroup: String)

case class KafkaTrafficStatsResult(
  lag:         Int,
  numConsumed: Int,
  numErrors:   Int)
    extends TrafficStatsResult {

  override val statsType: String = "Kafka consumer traffic check"

  override val error: Option[String] = {
    Option
      .when(lag > 1000)("Consumer lag too big")
      .orElse {
        val errorRatio = 100 - (numConsumed - numErrors)
        Option.when(errorRatio > 5)("Error ratio > 5%")
      }
  }
}

@activityInterface
trait DeploymentActivities {
  def deployService(params: DeployServiceParams): DeployServiceResult

  def httpTrafficStats(serviceDeploymentId: String, params: HttpTrafficStatsParams): HttpTrafficStatsResult

  def kafkaTrafficStats(serviceDeploymentId: String, params: KafkaTrafficStatsParams): KafkaTrafficStatsResult

  def rollbackService(serviceDeploymentId: String): Unit
}

object DeploymentActivitiesImpl {
  val make: URLayer[ZActivityRunOptions[Any], DeploymentActivities] =
    ZLayer.fromZIO(Ref.make(Map.empty[String, DeployServiceParams])) >>>
      ZLayer.derive[DeploymentActivitiesImpl]
}

class DeploymentActivitiesImpl(
  servicesRef:      Ref[Map[String, DeployServiceParams]]
)(implicit options: ZActivityRunOptions[Any])
    extends DeploymentActivities {

  override def deployService(params: DeployServiceParams): DeployServiceResult = {
    ZActivity.run {
      for {
        _         <- ZIO.logInfo(s"Deploying service=${params.name}")
        serviceId <- Random.nextUUID
        serviceDeploymentId = s"deploy-$serviceId"
        _ <- servicesRef.update(_.updated(serviceDeploymentId, params))
        _ <- ZIO.sleep(5.seconds)
      } yield DeployServiceResult(serviceDeploymentId)
    }
  }

  override def httpTrafficStats(serviceDeploymentId: String, params: HttpTrafficStatsParams): HttpTrafficStatsResult = {
    ZActivity.run {
      def badTraffic  = HttpTrafficStatsResult(numRequests = 1000, numErrors = 100) // 10% errors
      def goodTraffic = HttpTrafficStatsResult(numRequests = 1000, numErrors = 1)   // 0.1% errors

      for {
        services <- servicesRef.get
        service <- ZIO
                     .succeed(services.get(serviceDeploymentId))
                     .someOrFail(
                       new IllegalAccessException(s"Service deployment not found id=$serviceDeploymentId")
                     )
        _ <- ZIO.logInfo(s"HTTP traffic check service=${service.name} hostname=${params.hostname}")
        traffic <- Random.nextBoolean.map {
                     case true => goodTraffic
                     // sometimes the traffic check fails
                     case _ => if (service.name.contains("bad")) badTraffic else goodTraffic
                   }
      } yield traffic
    }
  }

  override def kafkaTrafficStats(
    serviceDeploymentId: String,
    params:              KafkaTrafficStatsParams
  ): KafkaTrafficStatsResult = {
    ZActivity.run {
      def badTraffic  = KafkaTrafficStatsResult(lag = 10000, numConsumed = 100, numErrors = 1) // big lag
      def goodTraffic = KafkaTrafficStatsResult(lag = 10, numConsumed = 100, numErrors = 1)    // good so far

      for {
        services <- servicesRef.get
        service <- ZIO
                     .succeed(services.get(serviceDeploymentId))
                     .someOrFail(
                       new IllegalAccessException(s"Service deployment not found id=$serviceDeploymentId")
                     )
        _ <- ZIO.logInfo(
               s"Kafka traffic check service=${service.name} topic=${params.topic} group=${params.consumerGroup}"
             )
        traffic <- Random.nextBoolean.map {
                     case true => goodTraffic
                     // sometimes the traffic check fails
                     case _ => if (service.name.contains("bad")) badTraffic else goodTraffic
                   }
      } yield traffic
    }
  }

  override def rollbackService(serviceDeploymentId: String): Unit = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Rolling back serviceDeploymentId=$serviceDeploymentId")
        _ <- servicesRef.update(_.removed(serviceDeploymentId))
        _ <- ZIO.sleep(5.seconds)
      } yield ()
    }
  }
}
