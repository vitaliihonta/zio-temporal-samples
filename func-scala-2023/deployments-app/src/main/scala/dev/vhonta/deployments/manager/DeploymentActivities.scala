package dev.vhonta.deployments.manager

import zio._
import zio.temporal._
import zio.temporal.activity._
import java.net.URL

case class DeployServiceParams(name: String)

case class DeployServiceResult(
  serviceDeploymentId: String)

case class HttpTrafficStatsParams(
  hostname: URL)

case class HttpTrafficStatsResult(
  numRequests:    Int,
  numErrors:      Int,
  numBadRequests: Int)

case class KafkaTrafficStatsParams(
  topic:         String,
  consumerGroup: String)

case class KafkaTrafficStatsResult(
  lag:         Int,
  numConsumed: Int,
  numErrors:   Int)

@activityInterface
trait DeploymentActivities {
  def deployService(params: DeployServiceParams): DeployServiceResult

  def httpTrafficStats(serviceDeploymentId: String): HttpTrafficStatsResult

  def kafkaTrafficStats(): KafkaTrafficStatsResult

  def rollbackService(serviceDeploymentId: String): Unit
}

class DeploymentActivitiesImpl(
  services:         Ref[Map[String, DeployServiceParams]]
)(implicit options: ZActivityRunOptions[Any])
    extends DeploymentActivities {
  override def deployService(params: DeployServiceParams): DeployServiceResult = ???

  override def httpTrafficStats(serviceDeploymentId: String): HttpTrafficStatsResult = ???

  override def kafkaTrafficStats(): KafkaTrafficStatsResult = ???

  override def rollbackService(serviceDeploymentId: String): Unit = ???
}
