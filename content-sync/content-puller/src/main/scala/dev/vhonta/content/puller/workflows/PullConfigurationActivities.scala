package dev.vhonta.content.puller.workflows

import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.puller.proto.GetConfigurationParams
import dev.vhonta.content.puller.{PullerConfig, YoutubePullerConfig, proto}

@activityInterface
trait PullConfigurationActivities {
  def getBasePullerConfig(params: GetConfigurationParams): proto.PullerConfig

  def getYoutubePullerConfig: proto.YoutubePullerConfig
}

object PullConfigurationActivitiesImpl {
  val make: URLayer[ZActivityRunOptions[Any], PullConfigurationActivities] =
    ZLayer.fromFunction(new PullConfigurationActivitiesImpl()(_: ZActivityRunOptions[Any]))
}

class PullConfigurationActivitiesImpl()(implicit options: ZActivityRunOptions[Any])
    extends PullConfigurationActivities {
  override def getBasePullerConfig(params: GetConfigurationParams): proto.PullerConfig =
    ZActivity.run {
      ZIO
        .config(PullerConfig.definition.nested("puller", params.integrationType.name))
        .map(pullerConfig =>
          proto.PullerConfig(
            pullInterval = pullerConfig.pullInterval.toProto,
            singlePullTimeout = pullerConfig.singlePullTimeout.toProto,
            datalakeOutputDir = pullerConfig.datalakeOutputDir
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
