package dev.vhonta.news.processor.workflow

import dev.vhonta.news.processor.proto.{RecommendationEngineParams, RecommendationEngineResult}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

case class RecommendationEngineException(msg: String) extends Exception(msg)

@activityInterface
trait NewsFeedRecommendationEngine {
  @throws[RecommendationEngineException]
  def makeRecommendations(params: RecommendationEngineParams): RecommendationEngineResult
}

object NewsFeedRecommendationEngineImpl {
  val make: URLayer[ZActivityOptions[Any], NewsFeedRecommendationEngine] =
    ZLayer.fromFunction(NewsFeedRecommendationEngineImpl()(_: ZActivityOptions[Any]))
}

case class NewsFeedRecommendationEngineImpl()(implicit options: ZActivityOptions[Any])
    extends NewsFeedRecommendationEngine {
  override def makeRecommendations(
    params: RecommendationEngineParams
  ): RecommendationEngineResult =
    ZActivity.run {
      for {
        _ <-
          ZIO.logInfo(
            s"Making recommendations for reader=${params.readerWithArticles.reader.id.fromProto} " +
              s"topic=${params.readerWithArticles.topic.id.fromProto} " +
              s"num_articles=${params.readerWithArticles.articles.size}"
          )
        // Pretty dumb algorithm =)
        chosenArticles <- ZIO.randomWith(_.shuffle(params.readerWithArticles.articles))
      } yield RecommendationEngineResult(
        articleIds = chosenArticles.view
          .take(5)
          .map(_.id)
          .toList
      )
    }
}
