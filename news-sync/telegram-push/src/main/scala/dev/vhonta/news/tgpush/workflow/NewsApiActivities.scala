package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.newsapi.{EverythingRequest, NewsApiClient, NewsApiRequestError, SortBy}
import dev.vhonta.news.repository.NewsFeedIntegrationRepository
import dev.vhonta.news.tgpush.proto.{
  StoreNewsApiIntegrationParams,
  StoreNewsApiIntegrationResult,
  TestApiKeyParams,
  TestApiKeyResult
}
import dev.vhonta.news.{NewsFeedIntegration, NewsFeedIntegrationDetails, NewsTopicLanguage, proto}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

@activityInterface
trait NewsApiActivities {

  def testApiKey(params: TestApiKeyParams): TestApiKeyResult

  def storeIntegration(params: StoreNewsApiIntegrationParams): StoreNewsApiIntegrationResult
}

object NewsApiActivitiesImpl {
  val make: URLayer[
    NewsApiClient with NewsFeedIntegrationRepository with ZActivityOptions[Any],
    NewsApiActivities
  ] =
    ZLayer.fromFunction(
      NewsApiActivitiesImpl(
        _: NewsApiClient,
        _: NewsFeedIntegrationRepository
      )(_: ZActivityOptions[Any])
    )
}

case class NewsApiActivitiesImpl(
  client:                NewsApiClient,
  integrationRepository: NewsFeedIntegrationRepository
)(implicit options:      ZActivityOptions[Any])
    extends NewsApiActivities {

  override def testApiKey(params: TestApiKeyParams): TestApiKeyResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo("Testing api key...")
        isValid <- client
                     .everything(
                       request = EverythingRequest(
                         query = "Science",
                         language = NewsTopicLanguage.English.code,
                         from = None,
                         to = None,
                         sortBy = SortBy.PublishedAt,
                         pageSize = 1,
                         page = 1
                       ),
                       apiKey = params.apiKey
                     )
                     .as(true)
                     .catchSome { case e: NewsApiRequestError =>
                       ZIO
                         .logWarning(s"API Key invalid: code=${e.code} message=${e.message}")
                         .as(false)
                     }
      } yield TestApiKeyResult(
        valid = isValid
      )
    }

  override def storeIntegration(params: StoreNewsApiIntegrationParams): StoreNewsApiIntegrationResult = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Creating a news api integration reader=${params.reader.fromProto}")
        integration <- integrationRepository.create(
                         NewsFeedIntegration(
                           id = -1,
                           reader = params.reader.fromProto,
                           integration = NewsFeedIntegrationDetails.NewsApi(
                             token = params.apiKey
                           )
                         )
                       )
      } yield StoreNewsApiIntegrationResult(
        integration = proto.NewsFeedIntegration(
          id = integration.id,
          readerId = integration.reader,
          integration = proto.NewsFeedIntegration.Integration.NewsApi(
            proto.NewsFeedIntegrationNewsApiDetails(params.apiKey)
          )
        )
      )
    }
  }
}
