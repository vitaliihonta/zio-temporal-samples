package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.proto.{ContentFeedIntegration, ContentFeedIntegrationType}
import dev.vhonta.content.puller.proto.{
  GetConfigurationParams,
  ListIntegrations,
  LoadPullerStatesParams,
  PullerConfig,
  PullerParams,
  UpsertPullerStateParams
}
import dev.vhonta.content.{PullerState, PullerStateValue}
import dev.vhonta.content.puller.workflows.PullConfigurationActivities
import dev.vhonta.content.puller.workflows.storage.DatabaseActivities
import org.slf4j.Logger
import zio._
import zio.temporal._
import zio.temporal.workflow._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import io.getquill.JsonbValue
import java.time.LocalDateTime
import scala.reflect.ClassTag

abstract class AsyncScheduledPullerWorkflow[
  IntegrationState <: PullerStateValue,
  PullParams <: PullerParams,
  PullerWorkflow <: BasePullWorkflow[PullParams]: IsWorkflow: ClassTag
](integrationType: ContentFeedIntegrationType)
    extends BaseScheduledPullerWorkflow {

  protected def convertIntegrationState(raw: PullerStateValue): Option[IntegrationState]

  protected def refreshIntegrationState(integrationId: Long, processedAt: LocalDateTime): IntegrationState

  protected def constructPullParams(
    integration:  ContentFeedIntegration,
    state:        Option[IntegrationState],
    startedAt:    LocalDateTime,
    pullerConfig: PullerConfig
  ): Option[PullParams]

  protected val logger: Logger         = ZWorkflow.makeLogger
  protected val thisWorkflowId: String = ZWorkflow.info.workflowId

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

  override def pullAll(): Unit = {
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

    val loadedStates = ZActivityStub.execute(
      databaseActivities.loadAllPullerStates(LoadPullerStatesParams(integrationType))
    )

    val states = loadedStates.states.view.flatMap { state =>
      for {
        v         <- PullerState.safeFromProto(state)
        converted <- convertIntegrationState(v.value.value)
      } yield v.integration -> converted
    }.toMap

    logger.info(s"Loaded puller states=$states")

    val pullParamsByIntegration: Map[Long, PullParams] = integrations.integrations.view.flatMap { integration =>
      constructPullParams(
        integration = integration,
        state = states.get(integration.id),
        startedAt = startedAt,
        pullerConfig = pullerConfig
      ).map(integration.id -> _)
    }.toMap

    val pullTasks = ZAsync.foreachPar(pullParamsByIntegration.toList) { case (integrationId, parameters) =>
      logger.info(
        s"Starting puller integrationId=$integrationId parameters=$parameters"
      )

      val pullerWorkflow = ZWorkflow
        .newChildWorkflowStub[PullerWorkflow]
        .withWorkflowId(s"$thisWorkflowId/$integrationType/$integrationId")
        // Limit the pull time
        .withWorkflowExecutionTimeout(pullerConfig.singlePullTimeout.fromProto[Duration])
        .build

      ZChildWorkflowStub
        .executeAsync(
          pullerWorkflow.pull(parameters)
        )
        .map { result =>
          logger.info(s"Puller integrationId=$integrationId processed ${result.processed} records")
          integrationId
        }
        .option
    }

    // Wait until all completed and update puller state
    val updatedStates = pullTasks.run.getOrThrow.view
      .flatMap(_.toList)
      .map { integrationId =>
        val value = refreshIntegrationState(integrationId, startedAt)
        PullerState(integrationId, JsonbValue(value)).toProto
      }
      .toList

    ZActivityStub.execute(
      databaseActivities.upsertPullerState(
        UpsertPullerStateParams(updatedStates)
      )
    )

    logger.info("Finished pulling!")
  }
}
