package dev.vhonta.news.processor.workflow

import dev.vhonta.news.processor.proto.{
  CheckRecommendationsExistParams,
  LoadReaderParams,
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
trait RecommendationsWorkflow {
  @workflowMethod
  def makeRecommendations(params: RecommendationsParams): Unit
}

class RecommendationsWorkflowImpl extends RecommendationsWorkflow {
  private val logger = ZWorkflow.getLogger(getClass)

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

  private val recommendationsEngine = ZWorkflow
    .newActivityStub[NewsFeedRecommendationEngine]
    .withStartToCloseTimeout(15.seconds)
    .build

  override def makeRecommendations(params: RecommendationsParams): Unit = {
    logger.info("Going to process reader")

    val checkResult = ZActivityStub.execute(
      processorActivities.checkRecommendationsExist(
        CheckRecommendationsExistParams(
          params.readerWithTopic,
          params.forDate
        )
      )
    )

    if (checkResult.exists) {
      logger.info("Recommendations already calculated, skip")
    } else {
      val readerWithArticles = ZActivityStub.execute(
        processorActivities.loadReaderWithArticles(
          LoadReaderParams(
            readerId = params.readerWithTopic.readerId,
            topicId = params.readerWithTopic.topicId,
            forDate = params.forDate
          )
        )
      )

      val recommendations = ZActivityStub.execute(
        recommendationsEngine.makeRecommendations(
          RecommendationEngineParams(
            readerWithArticles = readerWithArticles
          )
        )
      )

      ZActivityStub.execute(
        processorActivities.createRecommendations(
          SaveRecommendationsParams(
            readerWithTopic = params.readerWithTopic,
            forDate = params.forDate,
            articleIds = recommendations.articleIds
          )
        )
      )
    }
  }
}
