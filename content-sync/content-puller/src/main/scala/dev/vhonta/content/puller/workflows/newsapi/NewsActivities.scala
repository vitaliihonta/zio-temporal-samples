package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.newsapi.{EverythingRequest, EverythingResponse, NewsApiClient, NewsApiRequestError, SortBy}
import dev.vhonta.content.puller.proto.{NewsApiArticle, NewsApiArticles, NewsPullerActivityParameters, NewsSource}
import dev.vhonta.content.ProtoConverters._
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime

@activityInterface
trait NewsActivities {
  def fetchArticles(parameters: NewsPullerActivityParameters): NewsApiArticles
}

object NewsActivitiesImpl {
  val make: URLayer[NewsApiClient with ZActivityOptions[Any], NewsActivities] =
    ZLayer.fromFunction(NewsActivitiesImpl(_: NewsApiClient)(_: ZActivityOptions[Any]))
}

case class NewsActivitiesImpl(newsApi: NewsApiClient)(implicit options: ZActivityOptions[Any]) extends NewsActivities {
  override def fetchArticles(parameters: NewsPullerActivityParameters): NewsApiArticles = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Processing topic=${parameters.topic} page=${parameters.page}")
        everythingResponse <- newsApi
                                .everything(
                                  EverythingRequest(
                                    query = parameters.topic,
                                    language = parameters.language.fromProto.code,
                                    from = parameters.from.map(_.fromProto[LocalDateTime]),
                                    to = Some(parameters.to.fromProto[LocalDateTime]),
                                    sortBy = SortBy.Relevancy,
                                    pageSize = 99,
                                    page = parameters.page
                                  ),
                                  apiKey = parameters.apiKey
                                )
                                .catchSome { case NewsApiRequestError("maximumResultsReached", _) =>
                                  ZIO
                                    .logWarning(s"Reached maximum results topic=${parameters.topic}")
                                    .as(EverythingResponse(0, Nil))
                                }
      } yield {
        NewsApiArticles(
          articles = everythingResponse.articles.filter(_.title.nonEmpty).map { article =>
            NewsApiArticle(
              source = NewsSource(
                id = article.source.id,
                name = article.source.name
              ),
              author = article.author,
              title = article.title.get,
              description = article.description,
              url = article.url,
              date = article.publishedAt.toLocalDateTime.toProto,
              content = article.content
            )
          }
        )
      }
    }
  }
}
