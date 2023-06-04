package dev.vhonta.content.puller.workflows

import dev.vhonta.content.proto.ContentFeedIntegrationType
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.puller.proto.GetConfigurationParams
import dev.vhonta.content.puller.{PullerConfig, YoutubePullerConfig, proto}

@activityInterface
trait ConfigurationActivities {
  def getBasePullerConfig(params: GetConfigurationParams): proto.PullerConfig

  def getYoutubePullerConfig: proto.YoutubePullerConfig
}

object ConfigurationActivitiesImpl {
  val make: URLayer[ZActivityOptions[Any], ConfigurationActivities] =
    ZLayer.fromFunction(new ConfigurationActivitiesImpl()(_: ZActivityOptions[Any]))
}

class ConfigurationActivitiesImpl()(implicit options: ZActivityOptions[Any]) extends ConfigurationActivities {
  override def getBasePullerConfig(params: GetConfigurationParams): proto.PullerConfig =
    ZActivity.run {
      ZIO
        .config(PullerConfig.definition.nested("puller", params.integrationType.name))
        .map(pullerConfig =>
          proto.PullerConfig(
            pullInterval = pullerConfig.pullInterval.toProto,
            singlePullTimeout = pullerConfig.singlePullTimeout.toProto
          )
        )
    }

  override def getYoutubePullerConfig: proto.YoutubePullerConfig = {
    ZActivity.run {
      ZIO
        .config(YoutubePullerConfig.definition.nested("puller", "youtube"))
        .map(youtubePullerConfig => proto.YoutubePullerConfig(maxResults = youtubePullerConfig.maxResults))
    }
  }
}
