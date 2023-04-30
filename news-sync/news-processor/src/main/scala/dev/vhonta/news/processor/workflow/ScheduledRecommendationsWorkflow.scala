package dev.vhonta.news.processor.workflow

import dev.vhonta.news.processor.proto.{
  LoadReaderParams,
  ReaderWithArticles,
  RecommendationEngineParams,
  RecommendationsParams,
  SaveRecommendationsParams
}
import zio._
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.workflow._
import zio.temporal.protobuf.syntax._

@workflowInterface
trait ScheduledRecommendationsWorkflow {
  @workflowMethod
  def makeRecommendations(): Unit
}

class ScheduledRecommendationsWorkflowImpl extends ScheduledRecommendationsWorkflow {
  private val logger = ZWorkflow.getLogger(getClass)

  // TODO: make configurable
  private val processInterval      = 15.minutes
  private val singleProcessTimeout = 10.minutes

  private val processorActivities = ZWorkflow
    .newActivityStub[ProcessorActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default.withDoNotRetry(
        nameOf[ReaderNotFoundException],
        nameOf[TopicNotFoundException]
      )
    )
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[ScheduledRecommendationsWorkflow].build

  override def makeRecommendations(): Unit = {
    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val readersWithTopics = ZActivityStub.execute(
      processorActivities.loadAllReadersWithTopics()
    )

    logger.info(s"Have ${readersWithTopics.readersWithTopics.size} topics to process today=$startedAt")

    val processors = readersWithTopics.readersWithTopics.view.map { readerWithTopic =>
      val recommendationsWorkflow = ZWorkflow
        .newChildWorkflowStub[RecommendationsWorkflow]
        .withWorkflowId(
          s"${ZWorkflow.info.workflowId}/readers/${readerWithTopic.readerId.fromProto}/topics/${readerWithTopic.topicId.fromProto}"
        )
        .withWorkflowExecutionTimeout(singleProcessTimeout)
        .withRetryOptions(
          ZRetryOptions.default.withMaximumAttempts(2)
        )
        .build

      ZChildWorkflowStub.executeAsync(
        recommendationsWorkflow.makeRecommendations(
          RecommendationsParams(
            readerWithTopic,
            forDate = startedAt.toProto
          )
        )
      )
    }.toList

    // Wait for all processors to complete
    ZAsync.collectAllDiscard(processors).run.getOrThrow

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
