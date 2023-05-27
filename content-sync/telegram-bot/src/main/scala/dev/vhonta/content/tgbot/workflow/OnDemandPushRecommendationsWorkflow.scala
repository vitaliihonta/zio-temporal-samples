package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.tgbot.proto.{NotifySubscriberParams, PushRecommendationsParams}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import zio.temporal.workflow._
import zio.temporal.failure.ChildWorkflowFailure

@workflowInterface
trait OnDemandPushRecommendationsWorkflow {
  @workflowMethod
  def push(params: PushRecommendationsParams): Unit
}

class OnDemandPushRecommendationsWorkflowImpl extends OnDemandPushRecommendationsWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val telegramActivities = ZWorkflow
    .newActivityStub[TelegramActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        .withDoNotRetry(
          nameOf[SubscriberNotFoundException]
        )
    )
    .build

  override def push(params: PushRecommendationsParams): Unit = {
    logger.info(s"On-demand push")

    val pushRecommendationsWorkflow = ZWorkflow
      .newChildWorkflowStub[PushRecommendationsWorkflow]
      .withRetryOptions(
        ZRetryOptions.default
          .withMaximumAttempts(3)
          .withDoNotRetry(nameOf[SubscriberNotFoundException])
      )
      .build

    try {
      ZChildWorkflowStub.execute(
        pushRecommendationsWorkflow.push(params)
      )
    } catch {
      case _: ChildWorkflowFailure =>
        logger.warn("Push workflow failed")
        ZActivityStub.execute(
          telegramActivities.notifySubscriber(
            NotifySubscriberParams(
              subscriber = params.subscriberWithSettings.subscriber.id,
              message = "Ooops, something wrong happened \uD83D\uDE05"
            )
          )
        )
    }
  }
}
