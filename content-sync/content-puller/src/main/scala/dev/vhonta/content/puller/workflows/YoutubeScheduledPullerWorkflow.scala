package dev.vhonta.content.puller.workflows

import dev.vhonta.content.proto.ContentFeedIntegrationType
import dev.vhonta.content.puller.proto.{
  ListIntegrations,
  YoutubePullerInitialState,
  YoutubePullerIntegrationState,
  YoutubePullerParameters,
  YoutubePullerResetState
}
import zio._
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._
import java.time.LocalDateTime

@workflowInterface
trait YoutubeScheduledPullerWorkflow {
  @workflowMethod(name = "YoutubePullScheduler")
  def startPulling(initialState: YoutubePullerInitialState): Unit

  @signalMethod
  def resetState(command: YoutubePullerResetState): Unit

  @signalMethod
  def resetStateAll(): Unit
}

case class YoutubePullerWorkflowIntegrationState(lastProcessedAt: LocalDateTime)

class YoutubeScheduledPullerWorkflowImpl extends YoutubeScheduledPullerWorkflow {
  private val logger         = ZWorkflow.makeLogger
  private val state          = ZWorkflowState.emptyMap[Long, YoutubePullerWorkflowIntegrationState]
  private val thisWorkflowId = ZWorkflow.info.workflowId

  // TODO: make configurable
  private val pullInterval      = 15.minutes
  private val singlePullTimeout = 10.minutes
  private val maxResults        = 100

  private val databaseActivities = ZWorkflow
    .newActivityStub[DatabaseActivities]
    .withStartToCloseTimeout(3.minutes)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(3)
    )
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[YoutubeScheduledPullerWorkflow].build

  override def startPulling(initialState: YoutubePullerInitialState): Unit = {
    state := initialState.states.view
      .map(state =>
        state.integrationId -> YoutubePullerWorkflowIntegrationState(state.lastProcessedAt.fromProto[LocalDateTime])
      )
      .toMap

    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val youtubeIntegrations = ZActivityStub.execute(
      databaseActivities.loadIntegrations(
        ListIntegrations(ContentFeedIntegrationType.youtube)
      )
    )

    logger.info(s"Loaded ${youtubeIntegrations.integrations.size} youtube integrations")

    val subscriptionPullParameters = youtubeIntegrations.integrations.view.flatMap { integration =>
      integration.integration.youtube.map { youtubeDetails =>
        YoutubePullerParameters(
          integrationId = integration.id,
          minDate = state
            .get(integration.id)
            .map(_.lastProcessedAt)
            .getOrElse(startedAt.toLocalDate.atStartOfDay())
            .toProto,
          maxResults = maxResults
        )
      }
    }.toList

    // remove deleted integrations
    locally {
      val existingIntegrations = subscriptionPullParameters.view.map(_.integrationId.fromProto).toSet
      state.filterKeysInPlace(existingIntegrations.contains)
    }

    val pullTasks = ZAsync.foreachPar(subscriptionPullParameters) { parameters =>
      val integrationId = parameters.integrationId
      logger.info(
        s"Starting puller integrationId=$integrationId minDate=${parameters.minDate}"
      )

      val youtubePullSubscriptionsWorkflow = ZWorkflow
        .newChildWorkflowStub[YoutubePullSubscriptionsWorkflow]
        .withWorkflowId(s"youtube/$thisWorkflowId/integrations/$integrationId")
        // Limit the pull time
        .withWorkflowExecutionTimeout(singlePullTimeout)
        .build

      ZChildWorkflowStub
        .executeAsync(
          youtubePullSubscriptionsWorkflow.pull(parameters)
        )
        .map { result =>
          logger.info(s"Puller integrationId=$integrationId processed ${result.processed} records")
          integrationId
        }
        .option
    }

    // Wait until all completed and update puller state
    pullTasks.run.getOrThrow.flatMap(_.toList).foreach { integrationId =>
      state.update(integrationId, YoutubePullerWorkflowIntegrationState(lastProcessedAt = startedAt))
    }

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime  = pullInterval minus java.time.Duration.between(startedAt, finishedAt)

    logger.info(s"Next pull starts after $sleepTime")

    // Wait for the next run
    ZWorkflow.sleep(sleepTime)

    // Continue as new workflow
    ZWorkflowContinueAsNewStub.execute(
      nextRun.startPulling(
        YoutubePullerInitialState(
          states = state.snapshot.view.map { case (integrationId, integrationState) =>
            YoutubePullerIntegrationState(integrationId, integrationState.lastProcessedAt)
          }.toList
        )
      )
    )
  }

  override def resetState(command: YoutubePullerResetState): Unit = {
    val integrationId = command.integrationId
    logger.info(s"Resetting puller state integrationId=$integrationId")
    state -= integrationId
  }

  override def resetStateAll(): Unit = {
    logger.info("Resetting ALL puller state")
    state.clear()
  }
}
