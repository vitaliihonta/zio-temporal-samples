package dev.vhonta.content.processor.workflow

import dev.vhonta.content.processor.proto.RecommendationsParams
import zio._
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._
import zio.temporal.workflow._

@workflowInterface
trait ScheduledRecommendationsWorkflow {
  @workflowMethod
  def makeRecommendations(): Unit
}

class ScheduledRecommendationsWorkflowImpl extends ScheduledRecommendationsWorkflow {
  private val logger = ZWorkflow.makeLogger

  // TODO: make configurable
  private val processInterval      = 15.minutes
  private val singleProcessTimeout = 10.minutes

  private val processorActivities = ZWorkflow
    .newActivityStub[ProcessorActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default.withDoNotRetry(
        nameOf[SubscriberNotFoundException],
        nameOf[IntegrationNotFound]
      )
    )
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[ScheduledRecommendationsWorkflow].build

  override def makeRecommendations(): Unit = {
    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val subscribersWithIntegrations = ZActivityStub.execute(
      processorActivities.loadAllSubscribersWithIntegrations()
    )

    logger.info(s"Have ${subscribersWithIntegrations.values.size} topics to process today=$startedAt")

    val started: ZAsync[Unit] = ZAsync.foreachParDiscard(subscribersWithIntegrations.values) {
      subscriberWithIntegration =>
        val recommendationsWorkflow = ZWorkflow
          .newChildWorkflowStub[RecommendationsWorkflow]
          .withWorkflowId(
            s"${ZWorkflow.info.workflowId}/subscribers/${subscriberWithIntegration.subscriberId.fromProto}/int/${subscriberWithIntegration.integrationId.fromProto}"
          )
          .withWorkflowExecutionTimeout(singleProcessTimeout)
          .withRetryOptions(
            ZRetryOptions.default.withMaximumAttempts(2)
          )
          .build

        ZChildWorkflowStub.executeAsync(
          recommendationsWorkflow.makeRecommendations(
            RecommendationsParams(
              subscriberWithIntegration,
              forDate = startedAt.toProto
            )
          )
        )
    }

    // Wait for all processors to complete
    started.run.getOrThrow

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime  = processInterval minus java.time.Duration.between(startedAt, finishedAt)

    logger.info(s"Next processing starts after $sleepTime")

    // Wait for the next run
    ZWorkflow.sleep(sleepTime)

    // Continue as new workflow
    ZWorkflowContinueAsNewStub.execute(
      nextRun.makeRecommendations()
    )
  }
}
