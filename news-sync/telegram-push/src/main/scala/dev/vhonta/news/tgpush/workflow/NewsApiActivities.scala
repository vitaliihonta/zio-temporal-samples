package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.{NewsFeedIntegration, NewsFeedIntegrationDetails, NewsFeedTopic, NewsTopicLanguage, proto}
import dev.vhonta.news.client.{EverythingRequest, NewsApiClient, NewsApiRequestError, SortBy}
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, NewsFeedRepository}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.news.tgpush.proto.{
  AddTopicParams,
  CreateTopicParams,
  ListTopicsParams,
  ListTopicsResult,
  StoreNewsApiIntegrationParams,
  StoreNewsApiIntegrationResult,
  TestApiKeyParams,
  TestApiKeyResult
}
import dev.vhonta.news.ProtoConverters._

@activityInterface
trait NewsApiActivities {
  def listTopics(params: ListTopicsParams): ListTopicsResult

  def createTopic(params: CreateTopicParams): Unit

  def testApiKey(params: TestApiKeyParams): TestApiKeyResult

  def storeIntegration(params: StoreNewsApiIntegrationParams): StoreNewsApiIntegrationResult
}

object NewsApiActivitiesImpl {
  val make: URLayer[NewsApiClient with NewsFeedRepository with NewsFeedIntegrationRepository with ZActivityOptions[Any],
                    NewsApiActivities
  ] =
    ZLayer.fromFunction(
      NewsApiActivitiesImpl(
        _: NewsApiClient,
        _: NewsFeedRepository,
        _: NewsFeedIntegrationRepository
      )(_: ZActivityOptions[Any])
    )
}

case class NewsApiActivitiesImpl(
  client:                NewsApiClient,
  newsFeedRepository:    NewsFeedRepository,
  integrationRepository: NewsFeedIntegrationRepository
)(implicit options:      ZActivityOptions[Any])
    extends NewsApiActivities {

  override def listTopics(params: ListTopicsParams): ListTopicsResult = {
    ZActivity.run {
      for {
        _      <- ZIO.logInfo(s"Listing topics reader=${params.reader.fromProto}")
        topics <- newsFeedRepository.listTopics(readers = Some(Set(params.reader.fromProto)))
      } yield {
        ListTopicsResult(
          topics = topics.map { topic =>
            proto.NewsFeedTopic(
              id = topic.id,
              owner = topic.owner,
              topic = topic.topic,
              lang = topic.lang
            )
          }
        )
      }
    }
  }

  override def createTopic(params: CreateTopicParams): Unit =
    ZActivity.run {
      for {
        _       <- ZIO.logInfo(s"Creating topic")
        topicId <- ZIO.randomWith(_.nextUUID)
        _ <- newsFeedRepository.createTopic(
               NewsFeedTopic(
                 id = topicId,
                 owner = params.reader.fromProto,
                 topic = params.topic,
                 // TODO: make configurable
                 lang = NewsTopicLanguage.English
               )
             )
      } yield ()
    }

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
