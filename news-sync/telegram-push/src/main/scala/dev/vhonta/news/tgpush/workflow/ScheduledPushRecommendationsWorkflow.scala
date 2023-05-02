package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.tgpush.proto.{ListAllReadersParams, PushRecommendationsParams}
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

  // TODO: make configurable
  private val pushInterval      = 15.minutes
  private val singlePushTimeout = 10.minutes

  private val newsFeedActivities = ZWorkflow
    .newActivityStub[NewsFeedActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(3)
    )
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[ScheduledPushRecommendationsWorkflow].build

  override def start(): Unit = {
    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    logger.info("Tick")

    val readers = ZActivityStub.execute(
      newsFeedActivities.listAllReaders(
        ListAllReadersParams(
          now = startedAt.toProto,
          deltaMinutes = pushInterval.toMinutes
        )
      )
    )

    logger.info(s"Going to push ${readers.values.size} news feeds")

    val pushes = ZAsync.foreachParDiscard(readers.values) { readerWithSettings =>
      val pushRecommendationsWorkflow = ZWorkflow
        .newChildWorkflowStub[PushRecommendationsWorkflow]
        .withWorkflowId(s"${ZWorkflow.info.workflowId}/push/${readerWithSettings.reader.id.fromProto}")
        .withWorkflowExecutionTimeout(singlePushTimeout)
        .build

      ZChildWorkflowStub
        .executeAsync(
          pushRecommendationsWorkflow.push(
            PushRecommendationsParams(readerWithSettings, date = startedAt)
          )
        )
        .ignore
    }

    pushes.run.getOrThrow

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime  = pushInterval minus java.time.Duration.between(startedAt, finishedAt)

    logger.info(s"Next push starts after $sleepTime")

    // Wait for the next run
    ZWorkflow.sleep(sleepTime)

    ZWorkflowContinueAsNewStub.execute(
      nextRun.start()
    )
  }
}
