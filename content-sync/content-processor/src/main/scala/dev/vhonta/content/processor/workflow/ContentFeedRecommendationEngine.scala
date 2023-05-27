package dev.vhonta.content.processor.workflow

import dev.vhonta.content.processor.proto.{RecommendationEngineParams, RecommendationEngineResult}
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
              s"topic=${params.subscriberWithItems.topic.id.fromProto} " +
              s"num_items=${params.subscriberWithItems.items.size}"
          )
        // Pretty dumb algorithm =)
        chosenItems <- ZIO.randomWith(_.shuffle(params.subscriberWithItems.items))
      } yield RecommendationEngineResult(
        itemIds = chosenItems.view
          .take(5)
          .map(_.id)
          .toList
      )
    }
}
