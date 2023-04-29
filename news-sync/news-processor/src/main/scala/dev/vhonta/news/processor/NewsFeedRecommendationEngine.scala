package dev.vhonta.news.processor

import dev.vhonta.news.{NewsFeedArticle, NewsFeedRecommendationArticle, NewsFeedTopic, Reader}
import zio._
import java.util.UUID

case class RecommendationEngineException(msg: String) extends Exception(msg)

object NewsFeedRecommendationEngine {
  val make: ULayer[NewsFeedRecommendationEngine] =
    ZLayer.fromFunction(() => NewsFeedRecommendationEngine())
}

case class NewsFeedRecommendationEngine() {
  def makeRecommendations(
    recommendationId: UUID,
    reader:           Reader,
    topic:            NewsFeedTopic,
    articles:         List[NewsFeedArticle]
  ): IO[RecommendationEngineException, List[NewsFeedRecommendationArticle]] = {
    for {
      // Pretty dumb algorithm =)
      chosenArticles <- ZIO.randomWith(_.shuffle(articles))
    } yield chosenArticles.map { article =>
      NewsFeedRecommendationArticle(
        recommendation = recommendationId,
        article = article.id
      )
    }
  }
}
