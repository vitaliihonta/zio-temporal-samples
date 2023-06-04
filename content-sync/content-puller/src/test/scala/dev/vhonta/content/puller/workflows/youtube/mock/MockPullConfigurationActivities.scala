package dev.vhonta.content.puller.workflows.youtube.mock

import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.proto.ContentFeedIntegrationType
import dev.vhonta.content.puller.proto.GetConfigurationParams
import dev.vhonta.content.puller.workflows.PullConfigurationActivities
import dev.vhonta.content.puller.{PullerConfig, YoutubePullerConfig, proto}
import zio.temporal.protobuf.syntax._

case class MockPullConfigurationActivities(
  pullerConfig:        Map[ContentFeedIntegrationType, PullerConfig],
  youtubePullerConfig: YoutubePullerConfig)
    extends PullConfigurationActivities {

  override def getBasePullerConfig(params: GetConfigurationParams): proto.PullerConfig = {
    val config = pullerConfig(params.integrationType)
    proto.PullerConfig(
      pullInterval = config.pullInterval.toProto,
      singlePullTimeout = config.singlePullTimeout.toProto
    )
  }

  override def getYoutubePullerConfig: proto.YoutubePullerConfig = {
    proto.YoutubePullerConfig(maxResults = youtubePullerConfig.maxResults)
  }
}
