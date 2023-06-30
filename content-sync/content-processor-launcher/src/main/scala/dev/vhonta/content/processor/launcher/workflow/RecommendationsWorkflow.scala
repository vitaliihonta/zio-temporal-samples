package dev.vhonta.content.processor.launcher.workflow

import dev.vhonta.content.processor.proto.{
  CheckRecommendationsExistParams,
  LoadSubscriberParams,
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
  private val logger = ZWorkflow.makeLogger

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

  private val recommendationsEngine = ZWorkflow
    .newActivityStub[ContentFeedRecommendationEngine]
    .withStartToCloseTimeout(15.seconds)
    .build

  override def makeRecommendations(params: RecommendationsParams): Unit = {
    logger.info("Going to process subscriber")

    val checkResult = ZActivityStub.execute(
      processorActivities.checkRecommendationsExist(
        CheckRecommendationsExistParams(
          params.subscriberWithIntegration,
          params.forDate
        )
      )
    )

    if (checkResult.exists) {
      logger.info("Recommendations already calculated, skip")
    } else {
      val subscriberWithItems = ZActivityStub.execute(
        processorActivities.loadSubscriberWithItems(
          LoadSubscriberParams(
            subscriberId = params.subscriberWithIntegration.subscriberId,
            integrationId = params.subscriberWithIntegration.integrationId,
            forDate = params.forDate
          )
        )
      )

      val recommendations = ZActivityStub.execute(
        recommendationsEngine.makeRecommendations(
          RecommendationEngineParams(
            subscriberWithItems = subscriberWithItems
          )
        )
      )

      ZActivityStub.execute(
        processorActivities.createRecommendations(
          SaveRecommendationsParams(
            subscriberWithIntegration = params.subscriberWithIntegration,
            forDate = params.forDate,
            itemIds = recommendations.itemIds
          )
        )
      )
    }
  }
}
