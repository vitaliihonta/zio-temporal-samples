package dev.vhonta.deployments.manager

import zio._
import zio.temporal._
import zio.temporal.activity._

import java.net.URL
import java.time.Instant

case class DeployServiceParams(id: String)

case class DeployServiceResult(
  /*might contain some useful information*/
)

sealed trait TrafficStatsResult {
  def error: Option[String]
  def statsType: String

}

case class HttpTrafficStatsParams(
  hostname: URL)

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

  def httpTrafficStats(serviceId: String, params: HttpTrafficStatsParams): HttpTrafficStatsResult

  def kafkaTrafficStats(serviceId: String, params: KafkaTrafficStatsParams): KafkaTrafficStatsResult

  def rollbackService(serviceId: String): Unit
}

object DeploymentActivitiesImpl {
  val make: URLayer[ZActivityRunOptions[Any], DeploymentActivities] =
    ZLayer.fromZIO(Ref.make(Map.empty[String, DeploymentMeta])) >>>
      ZLayer.derive[DeploymentActivitiesImpl]

  private case class DeploymentMeta(deployedAt: Instant)
}

class DeploymentActivitiesImpl(
  servicesRef:      Ref[Map[String, DeploymentActivitiesImpl.DeploymentMeta]]
)(implicit options: ZActivityRunOptions[Any])
    extends DeploymentActivities {

  import DeploymentActivitiesImpl.DeploymentMeta

  override def deployService(params: DeployServiceParams): DeployServiceResult = {
    ZActivity.run {
      for {
        _          <- ZIO.logInfo(s"Deploying service=${params.id}")
        _          <- ZIO.sleep(5.seconds)
        deployedAt <- Clock.instant
        _          <- servicesRef.update(_.updated(params.id, DeploymentMeta(deployedAt)))
      } yield DeployServiceResult()
    }
  }

  override def httpTrafficStats(serviceId: String, params: HttpTrafficStatsParams): HttpTrafficStatsResult = {
    ZActivity.run {
      def badTraffic  = HttpTrafficStatsResult(numRequests = 1000, numErrors = 100) // 10% errors
      def goodTraffic = HttpTrafficStatsResult(numRequests = 1000, numErrors = 1)   // 0.1% errors

      for {
        services <- servicesRef.get
        // checking service existence
        _ <- ZIO
               .succeed(services.get(serviceId))
               .someOrFail(
                 new IllegalAccessException(s"Service deployment not found id=$serviceId")
               )
        _ <- ZIO.logInfo(s"HTTP traffic check service=$serviceId hostname=${params.hostname}")
        traffic <- Random.nextBoolean.map {
                     case true => goodTraffic
                     // sometimes the traffic check fails
                     case _ => if (serviceId.contains("bad")) badTraffic else goodTraffic
                   }
      } yield traffic
    }
  }

  override def kafkaTrafficStats(
    serviceId: String,
    params:    KafkaTrafficStatsParams
  ): KafkaTrafficStatsResult = {
    ZActivity.run {
      def badTraffic  = KafkaTrafficStatsResult(lag = 10000, numConsumed = 100, numErrors = 1) // big lag
      def goodTraffic = KafkaTrafficStatsResult(lag = 10, numConsumed = 100, numErrors = 1)    // good so far

      for {
        services <- servicesRef.get
        // checking service existence
        _ <- ZIO
               .succeed(services.get(serviceId))
               .someOrFail(
                 new IllegalAccessException(s"Service deployment not found id=$serviceId")
               )
        _ <- ZIO.logInfo(
               s"Kafka traffic check service=${serviceId} topic=${params.topic} group=${params.consumerGroup}"
             )
        traffic <- Random.nextBoolean.map {
                     case true => goodTraffic
                     // sometimes the traffic check fails
                     case _ => if (serviceId.contains("bad")) badTraffic else goodTraffic
                   }
      } yield traffic
    }
  }

  override def rollbackService(serviceId: String): Unit = {
    ZActivity.run {
      for {
        services <- servicesRef.get
        // checking service existence
        serviceMeta <- ZIO
                         .succeed(services.get(serviceId))
                         .someOrFail(
                           new IllegalAccessException(s"Service deployment not found id=$serviceId")
                         )
        now <- Clock.instant
        timeInProd = Duration.fromInterval(serviceMeta.deployedAt, now)
        _ <- ZIO.logInfo(s"Rolling back service=$serviceId timeInProd=$timeInProd")
        _ <- servicesRef.update(_.removed(serviceId))
        _ <- ZIO.sleep(5.seconds)
      } yield ()
    }
  }
}
