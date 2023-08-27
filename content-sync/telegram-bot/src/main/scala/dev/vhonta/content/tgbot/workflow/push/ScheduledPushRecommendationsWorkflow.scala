package dev.vhonta.content.tgbot.workflow.push

import dev.vhonta.content.tgbot.proto.{ListAllSubscribersParams, PushRecommendationsParams}
import dev.vhonta.content.tgbot.workflow.common.ContentFeedActivities
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import zio.temporal.workflow._

@workflowInterface
trait ScheduledPushRecommendationsWorkflow {
  @workflowMethod
  def start(): Unit
}

class ScheduledPushRecommendationsWorkflowImpl extends ScheduledPushRecommendationsWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val contentFeedActivities = ZWorkflow
    .newActivityStub[ContentFeedActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(3)
    )
    .build

  private val configurationActivities = ZWorkflow
    .newActivityStub[PushConfigurationActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withDoNotRetry(nameOf[Config.Error])
    )
    .build

  override def start(): Unit = {
    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    logger.info("Tick")

    val pushConfig = ZActivityStub.execute(
      configurationActivities.getPushConfiguration
    )

    val subscribers = ZActivityStub.execute(
      contentFeedActivities.listAllSubscribers(
        ListAllSubscribersParams(
          now = startedAt.toProto,
          deltaMinutes = pushConfig.pushInterval.fromProto[Duration].toMinutes
        )
      )
    )

    logger.info(s"Going to push ${subscribers.values.size} news feeds")

    val pushes = ZAsync.foreachParDiscard(subscribers.values) { subscriberWithSettings =>
      val pushRecommendationsWorkflow = ZWorkflow
        .newChildWorkflowStub[PushRecommendationsWorkflow]
        .withWorkflowId(s"${ZWorkflow.info.workflowId}/push/${subscriberWithSettings.subscriber.id.fromProto}")
        .withWorkflowExecutionTimeout(pushConfig.singlePushTimeout.fromProto[Duration])
        .build

      ZChildWorkflowStub
        .executeAsync(
          pushRecommendationsWorkflow.push(
            PushRecommendationsParams(subscriberWithSettings, date = startedAt)
          )
        )
        .ignore
    }

    pushes.run.getOrThrow

    logger.info("Finished push!")
  }
}
