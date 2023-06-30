package dev.vhonta.content.processor.launcher.workflow

import dev.vhonta.content.processor.proto.{RecommendationEngineParams, RecommendationEngineResult}
import dev.vhonta.content.proto.ContentFeedItem
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

case class RecommendationEngineException(msg: String) extends Exception(msg)

@activityInterface
trait ContentFeedRecommendationEngine {

  @throws[RecommendationEngineException]
  def makeRecommendations(params: RecommendationEngineParams): RecommendationEngineResult
}

object ContentFeedRecommendationEngineImpl {
  val make: URLayer[ZActivityOptions[Any], ContentFeedRecommendationEngine] =
    ZLayer.fromFunction(ContentFeedRecommendationEngineImpl()(_: ZActivityOptions[Any]))
}

case class ContentFeedRecommendationEngineImpl()(implicit options: ZActivityOptions[Any])
    extends ContentFeedRecommendationEngine {

  override def makeRecommendations(
    params: RecommendationEngineParams
  ): RecommendationEngineResult =
    ZActivity.run {
      for {
        _ <-
          ZIO.logInfo(
            s"Making recommendations for subscriber=${params.subscriberWithItems.subscriber.id.fromProto} " +
              s"integration=${params.subscriberWithItems.integration.id.fromProto} " +
              s"num_items=${params.subscriberWithItems.items.size}"
          )
        chosenItems <- choseRecommendations(params)
      } yield RecommendationEngineResult(
        itemIds = chosenItems.view
          .map(_.id)
          .toList
      )
    }

  // Pretty dumb algorithm =)
  private def choseRecommendations(params: RecommendationEngineParams): Task[List[ContentFeedItem]] = {
    ZIO
      .randomWith(
        _.shuffle(params.subscriberWithItems.items)
      )
      .map(_.view.take(5).toList)
  }
}
