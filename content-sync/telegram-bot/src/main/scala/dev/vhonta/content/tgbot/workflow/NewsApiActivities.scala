package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.newsapi.{EverythingRequest, NewsApiClient, NewsApiRequestError, SortBy}
import dev.vhonta.content.repository.ContentFeedIntegrationRepository
import dev.vhonta.content.tgbot.proto.{
  StoreNewsApiIntegrationParams,
  StoreNewsApiIntegrationResult,
  TestApiKeyParams,
  TestApiKeyResult
}
import dev.vhonta.content.{ContentFeedIntegration, ContentFeedIntegrationDetails, ContentLanguage, proto}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

@activityInterface
trait NewsApiActivities {

  def testApiKey(params: TestApiKeyParams): TestApiKeyResult

  def storeNewsApiIntegration(params: StoreNewsApiIntegrationParams): StoreNewsApiIntegrationResult
}

object NewsApiActivitiesImpl {
  val make: URLayer[
    NewsApiClient with ContentFeedIntegrationRepository with ZActivityRunOptions[Any],
    NewsApiActivities
  ] =
    ZLayer.fromFunction(
      NewsApiActivitiesImpl(
        _: NewsApiClient,
        _: ContentFeedIntegrationRepository
      )(_: ZActivityRunOptions[Any])
    )
}

case class NewsApiActivitiesImpl(
  client:                NewsApiClient,
  integrationRepository: ContentFeedIntegrationRepository
)(implicit options:      ZActivityRunOptions[Any])
    extends NewsApiActivities {

  override def testApiKey(params: TestApiKeyParams): TestApiKeyResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo("Testing api key...")
        isValid <- ZIO.succeed(validateApiKeyFormat(params.apiKey)) && {
                     client
                       .everything(
                         request = EverythingRequest(
                           query = "Science",
                           language = ContentLanguage.English.code,
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
                   }
      } yield TestApiKeyResult(
        valid = isValid
      )
    }

  override def storeNewsApiIntegration(params: StoreNewsApiIntegrationParams): StoreNewsApiIntegrationResult = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Creating a news api integration subscriber=${params.subscriber.fromProto}")
        integration <- integrationRepository.create(
                         ContentFeedIntegration(
                           id = -1,
                           subscriber = params.subscriber.fromProto,
                           integration = ContentFeedIntegrationDetails.NewsApi(
                             token = params.apiKey
                           )
                         )
                       )
      } yield StoreNewsApiIntegrationResult(
        integration = proto.ContentFeedIntegration(
          id = integration.id,
          subscriber = integration.subscriber,
          integration = proto.ContentFeedIntegrationNewsApiDetails(params.apiKey)
        )
      )
    }
  }

  private val apiKeyRegex = """^[\x21-\x7E\x80-\xFF]*$""".r
  private def validateApiKeyFormat(apiKey: String): Boolean = {
    apiKeyRegex.matches(apiKey)
  }
}
