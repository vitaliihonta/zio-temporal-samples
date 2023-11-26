package dev.vhonta.deployments.manager

import java.net.URL
import enumeratum.{Enum, EnumEntry}
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[ServiceAspect.HttpApi], name = "http_api"),
    new JsonSubTypes.Type(value = classOf[ServiceAspect.KafkaConsumer], name = "kafka_consumer")
  )
)
sealed trait ServiceAspect
object ServiceAspect {
  case class HttpApi(address: URL) extends ServiceAspect

  case class KafkaConsumer(topic: String, consumerGroup: String) extends ServiceAspect
}

case class ServiceDeploymentRequest(
  name:    String,
  aspects: List[ServiceAspect])

case class DeploymentParams(deployments: List[ServiceDeploymentRequest])

sealed trait ServiceDeploymentStatus extends EnumEntry
object ServiceDeploymentStatus extends Enum[ServiceDeploymentStatus] {
  case object InProgress  extends ServiceDeploymentStatus
  case object RollingBack extends ServiceDeploymentStatus
  case object Failed      extends ServiceDeploymentStatus
  case object Completed   extends ServiceDeploymentStatus

  override val values = findValues
}

case class DeploymentProgressItem(
  name:    String,
  status:  ServiceDeploymentStatus,
  failure: Option[String])

case class DeploymentProgress(deployments: List[DeploymentProgressItem])

sealed trait DeploymentResultStatus extends EnumEntry
object DeploymentResultStatus extends Enum[DeploymentResultStatus] {
  case object Failed    extends DeploymentResultStatus
  case object Completed extends DeploymentResultStatus

  override val values = findValues
}

case class DeploymentResultItem(
  name:    String,
  failure: Option[String])

case class DeploymentResult(status: DeploymentResultStatus, deployments: List[DeploymentResultItem])
