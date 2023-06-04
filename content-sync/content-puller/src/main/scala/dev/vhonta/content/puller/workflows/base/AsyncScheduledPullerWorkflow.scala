package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.proto.{ContentFeedIntegration, ContentFeedIntegrationType}
import dev.vhonta.content.puller.proto.{
  GetConfigurationParams,
  ListIntegrations,
  PullerParams,
  PullerResetState,
  ScheduledPullerParams
}
import dev.vhonta.content.puller.workflows.{PullConfigurationActivities, DatabaseActivities}
import org.slf4j.Logger
import zio._
import zio.temporal._
import zio.temporal.state._
import zio.temporal.workflow._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import java.time.LocalDateTime
import scala.reflect.ClassTag

abstract class AsyncScheduledPullerWorkflow[
  InitialState <: ScheduledPullerParams,
  IntegrationState,
  PullParams <: PullerParams,
  PullerWorkflow <: BasePullWorkflow[PullParams]: IsWorkflow: ClassTag,
  Self <: BaseScheduledPullerWorkflow[InitialState]: IsWorkflow: ClassTag
](integrationType: ContentFeedIntegrationType)
    extends BaseScheduledPullerWorkflow[InitialState] {

  protected def initializeState(initialState: InitialState): Map[Long, IntegrationState]

  protected def stateForNextRun(current: Map[Long, IntegrationState]): InitialState

  protected def refreshIntegrationState(integrationId: Long, processedAt: LocalDateTime): IntegrationState

  protected def constructPullParams(
    integration: ContentFeedIntegration,
    state:       Option[IntegrationState],
    startedAt:   LocalDateTime
  ): Option[PullParams]

  protected val logger: Logger         = ZWorkflow.makeLogger
  protected val thisWorkflowId: String = ZWorkflow.info.workflowId

  private val state = ZWorkflowState.emptyMap[Long, IntegrationState]

  protected val databaseActivities: ZActivityStub.Of[DatabaseActivities] =
    ZWorkflow
      .newActivityStub[DatabaseActivities]
      .withStartToCloseTimeout(3.minutes)
      .withRetryOptions(
        ZRetryOptions.default.withMaximumAttempts(3)
      )
      .build

  protected val configurationActivities: ZActivityStub.Of[PullConfigurationActivities] =
    ZWorkflow
      .newActivityStub[PullConfigurationActivities]
      .withStartToCloseTimeout(30.seconds)
      .withRetryOptions(
        ZRetryOptions.default.withDoNotRetry(nameOf[Config.Error])
      )
      .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[Self].build

  override def startPulling(params: InitialState): Unit = {
    state := initializeState(params)

    val pullerConfig = ZActivityStub.execute(
      configurationActivities.getBasePullerConfig(GetConfigurationParams(integrationType))
    )

    logger.info(s"Using puller config: $pullerConfig")

    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val integrations = ZActivityStub.execute(
      databaseActivities.loadIntegrations(
        ListIntegrations(integrationType)
      )
    )

    logger.info(s"Loaded ${integrations.integrations.size} $integrationType integrations")

    val pullParamsByIntegration: Map[Long, PullParams] = integrations.integrations.view.flatMap { integration =>
      constructPullParams(
        integration = integration,
        state = state.get(integration.id),
        startedAt = startedAt
      ).map(integration.id -> _)
    }.toMap

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
        .withWorkflowExecutionTimeout(pullerConfig.singlePullTimeout.fromProto)
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
      state.update(
        integrationId,
        refreshIntegrationState(integrationId, startedAt)
      )
    }

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime  = pullerConfig.pullInterval.fromProto minus java.time.Duration.between(startedAt, finishedAt)

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

  override def resetState(command: PullerResetState): Unit = {
    val integrationId = command.integrationId
    logger.info(s"Resetting puller state integrationId=$integrationId")
    state -= integrationId
  }

  override def resetStateAll(): Unit = {
    logger.info("Resetting ALL puller state")
    state.clear()
  }
}
