package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationType,
  ContentFeedIntegrationYoutubeDetails
}
import dev.vhonta.content.puller.proto.{PullerConfig, YoutubePullerParameters}
import dev.vhonta.content.PullerStateValue
import dev.vhonta.content.puller.workflows.base.{AsyncScheduledPullerWorkflow, BaseScheduledPullerWorkflow}
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime

@workflowInterface
trait YoutubeScheduledPullerWorkflow extends BaseScheduledPullerWorkflow

class YoutubeScheduledPullerWorkflowImpl
    extends AsyncScheduledPullerWorkflow[
      PullerStateValue.Youtube,
      YoutubePullerParameters,
      YoutubePullWorkflow,
    ](ContentFeedIntegrationType.youtube)
    with YoutubeScheduledPullerWorkflow {

  override protected def constructPullParams(
    integration:  ContentFeedIntegration,
    state:        Option[PullerStateValue.Youtube],
    startedAt:    LocalDateTime,
    pullerConfig: PullerConfig
  ): Option[YoutubePullerParameters] = {
    integration.integration match {
      case _: ContentFeedIntegrationYoutubeDetails =>
        val youtubePullerConfig = ZActivityStub.execute(
          configurationActivities.getYoutubePullerConfig
        )

        Some(
          YoutubePullerParameters(
            integrationId = integration.id,
            minDate = state
              .map(_.lastProcessedAt.toProto)
              .getOrElse(startedAt.toLocalDate.minusDays(1).atStartOfDay().toProto),
            maxResults = youtubePullerConfig.maxResults,
            datalakeOutputDir = pullerConfig.datalakeOutputDir
          )
        )
      case _ => None
    }
  }

  override protected def refreshIntegrationState(
    integrationId: Long,
    processedAt:   LocalDateTime
  ): PullerStateValue.Youtube =
    PullerStateValue.Youtube(processedAt)

  override protected def convertIntegrationState(raw: PullerStateValue): Option[PullerStateValue.Youtube] =
    raw match {
      case youtube: PullerStateValue.Youtube => Some(youtube)
      case _                                 => None
    }
}
