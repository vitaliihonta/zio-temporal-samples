package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.proto.{ContentFeedIntegration, ContentFeedIntegrationType}
import dev.vhonta.content.puller.proto.{ListIntegrations, YoutubePullerResetState}
import dev.vhonta.content.puller.workflows.DatabaseActivities
import scalapb.GeneratedMessage
import zio._
import zio.temporal._
import zio.temporal.state._
import zio.temporal.workflow._
import zio.temporal.activity._
import java.time.LocalDateTime
import scala.reflect.ClassTag

trait BaseIntegrationState[Self <: BaseIntegrationState[Self]] {
  def lastProcessedAt: LocalDateTime
  def withProcessedAt(value: LocalDateTime): Self
}

// TODO: fix in zio-temporal
abstract class AsyncScheduledPullerWorkflow[
  InitialState <: GeneratedMessage,
  IntegrationState <: BaseIntegrationState[IntegrationState],
  PullParams <: GeneratedMessage,
  PullerWorkflow <: BasePullWorkflow[PullParams]: IsWorkflow: ClassTag,
  Self <: BaseScheduledPullerWorkflow[InitialState]: IsWorkflow: ClassTag
](integrationType: ContentFeedIntegrationType)
    extends BaseScheduledPullerWorkflow[InitialState] {

  protected def initializeState(initialState: InitialState): Map[Long, IntegrationState]

  protected def stateForNextRun(current: Map[Long, IntegrationState]): InitialState

  protected def constructPullParams(integration: ContentFeedIntegration): Option[PullParams]

  private val logger         = ZWorkflow.makeLogger
  private val state          = ZWorkflowState.emptyMap[Long, IntegrationState]
  private val thisWorkflowId = ZWorkflow.info.workflowId

  // TODO: make configurable
  private val pullInterval      = 15.minutes
  private val singlePullTimeout = 10.minutes

  private val databaseActivities = ZWorkflow
    .newActivityStub[DatabaseActivities]
    .withStartToCloseTimeout(3.minutes)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(3)
    )
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[Self].build

  override def startPulling(initialState: InitialState): Unit = {
    state := initializeState(initialState)

    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val integrations = ZActivityStub.execute(
      databaseActivities.loadIntegrations(
        ListIntegrations(integrationType)
      )
    )

    logger.info(s"Loaded ${integrations.integrations.size} $integrationType integrations")

    val pullParamsByIntegration: Map[Long, PullParams] = integrations.integrations.view
      .flatMap(integration => constructPullParams(integration).map(integration.id -> _))
      .toMap

    // remove deleted integrations
    locally {
      state.filterKeysInPlace(pullParamsByIntegration.contains)
    }

    val pullTasks = ZAsync.foreachPar(pullParamsByIntegration.toList) { case (integrationId, parameters) =>
      logger.info(
        s"Starting puller integrationId=$integrationId parameters=$parameters"
      )

      val pullerWorkflow = ZWorkflow
        .newChildWorkflowStub[PullerWorkflow]
        .withWorkflowId(s"$thisWorkflowId/$integrationType/$integrationId")
        // Limit the pull time
        .withWorkflowExecutionTimeout(singlePullTimeout)
        .build

      ZChildWorkflowStub
        .executeAsync(
          pullerWorkflow.pull(parameters: PullParams)
        )
        .map { result =>
          logger.info(s"Puller integrationId=$integrationId processed ${result.processed} records")
          integrationId
        }
        .option
    }

    // Wait until all completed and update puller state
    pullTasks.run.getOrThrow.flatMap(_.toList).foreach { integrationId =>
      state.update(integrationId, state(integrationId).withProcessedAt(startedAt))
    }

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime  = pullInterval minus java.time.Duration.between(startedAt, finishedAt)

    logger.info(s"Next pull starts after $sleepTime")

    // Wait for the next run
    ZWorkflow.sleep(sleepTime)

    // Continue as new workflow
    ZWorkflowContinueAsNewStub.execute(
      nextRun.startPulling(
        stateForNextRun(state.snapshot)
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
