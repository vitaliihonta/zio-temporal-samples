package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.tgpush.proto.{NotifyReaderParams, PushRecommendationsParams}
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
  private val logger = ZWorkflow.getLogger(getClass)

  private val telegramActivities = ZWorkflow
    .newActivityStub[TelegramActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        .withDoNotRetry(
          nameOf[ReaderNotFoundException]
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
          .withDoNotRetry(nameOf[ReaderNotFoundException])
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
          telegramActivities.notifyReader(
            NotifyReaderParams(
              reader = params.readerWithSettings.reader.id,
              message = "Ooops, something wrong happened \uD83D\uDE05"
            )
          )
        )
    }
  }
}
