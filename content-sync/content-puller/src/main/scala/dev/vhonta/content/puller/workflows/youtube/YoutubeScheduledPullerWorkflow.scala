package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationType,
  ContentFeedIntegrationYoutubeDetails
}
import dev.vhonta.content.puller.proto.{
  YoutubePullerInitialState,
  YoutubePullerIntegrationState,
  YoutubePullerParameters
}
import dev.vhonta.content.puller.workflows.base.{AsyncScheduledPullerWorkflow, BaseScheduledPullerWorkflow}
import zio.temporal._
import zio.temporal.protobuf.syntax._

import java.time.LocalDateTime

@workflowInterface
trait YoutubeScheduledPullerWorkflow extends BaseScheduledPullerWorkflow[YoutubePullerInitialState]

class YoutubeScheduledPullerWorkflowImpl
    extends AsyncScheduledPullerWorkflow[
      YoutubePullerInitialState,
      YoutubePullerIntegrationState,
      YoutubePullerParameters,
      YoutubePullWorkflow,
      YoutubeScheduledPullerWorkflow
    ](ContentFeedIntegrationType.youtube)
    with YoutubeScheduledPullerWorkflow {

  override protected def initializeState(
    initialState: YoutubePullerInitialState
  ): Map[Long, YoutubePullerIntegrationState] = {
    initialState.states.view.map { state =>
      state.integrationId -> YoutubePullerIntegrationState(
        integrationId = state.integrationId,
        lastProcessedAt = state.lastProcessedAt
      )
    }.toMap
  }

  override protected def stateForNextRun(
    current: Map[Long, YoutubePullerIntegrationState]
  ): YoutubePullerInitialState = {
    YoutubePullerInitialState(
      states = current.view.map { case (integrationId, integrationState) =>
        YoutubePullerIntegrationState(integrationId, integrationState.lastProcessedAt)
      }.toList
    )
  }

  // TODO: make configurable
  private val maxResults = 100

  override protected def constructPullParams(
    integration: ContentFeedIntegration,
    state:       Option[YoutubePullerIntegrationState],
    startedAt:   LocalDateTime
  ): Option[YoutubePullerParameters] = {
    integration.integration match {
      case _: ContentFeedIntegrationYoutubeDetails =>
        Some(
          YoutubePullerParameters(
            integrationId = integration.id,
            minDate = state
              .map(_.lastProcessedAt)
              .getOrElse(startedAt.toLocalDate.atStartOfDay().toProto),
            maxResults = maxResults
          )
        )
      case _ => None
    }
  }

  override protected def refreshIntegrationState(
    integrationId: Long,
    processedAt:   LocalDateTime
  ): YoutubePullerIntegrationState =
    YoutubePullerIntegrationState(integrationId, processedAt.toProto)
}
