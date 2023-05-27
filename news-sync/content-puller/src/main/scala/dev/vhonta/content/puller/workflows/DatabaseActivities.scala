package dev.vhonta.content.puller.workflows

import dev.vhonta.content.{ContentFeedItem, ContentFeedIntegrationDetails}
import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationNewsApiDetails,
  ContentFeedIntegrationYoutubeDetails,
  ContentFeedTopic
}
import dev.vhonta.content.puller.proto.{
  ListIntegrations,
  ListTopics,
  NewsApiArticles,
  ContentFeedIntegrations,
  NewsSyncTopics,
  StoreArticlesParameters
}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime

@activityInterface
trait DatabaseActivities {
  def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations

  def loadNewsTopics(list: ListTopics): NewsSyncTopics

  def store(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit
}

object DatabaseActivitiesImpl {
  val make: URLayer[ContentFeedRepository with ContentFeedIntegrationRepository with ZActivityOptions[Any],
                    DatabaseActivities
  ] =
    ZLayer.fromFunction(
      DatabaseActivitiesImpl(_: ContentFeedRepository, _: ContentFeedIntegrationRepository)(_: ZActivityOptions[Any])
    )
}

case class DatabaseActivitiesImpl(
  newsFeedRepository:     ContentFeedRepository,
  integrationsRepository: ContentFeedIntegrationRepository
)(implicit options:       ZActivityOptions[Any])
    extends DatabaseActivities {

  override def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Loading integrations type=${list.integrationType}")
        integrations <- integrationsRepository.findByType(
                          list.integrationType.fromProto
                        )
      } yield ContentFeedIntegrations(
        integrations.map(integration =>
          ContentFeedIntegration(
            id = integration.id,
            subscriber = integration.subscriber,
            // TODO: decouple conversion
            integration = integration.integration match {
              case ContentFeedIntegrationDetails.NewsApi(token) =>
                ContentFeedIntegration.Integration.NewsApi(
                  ContentFeedIntegrationNewsApiDetails(token)
                )
              case ContentFeedIntegrationDetails.Youtube() =>
                ContentFeedIntegration.Integration.Youtube(
                  ContentFeedIntegrationYoutubeDetails()
                )
            }
          )
        )
      )
    }

  override def loadNewsTopics(list: ListTopics): NewsSyncTopics =
    ZActivity.run {
      for {
        _      <- ZIO.logInfo("Loading news topics...")
        topics <- newsFeedRepository.listTopics(subscribers = Some(list.subscribers.map(_.fromProto).toSet))
      } yield NewsSyncTopics(
        topics = topics.map { topic =>
          ContentFeedTopic(
            id = topic.id.toProto,
            owner = topic.owner,
            topic = topic.topic,
            lang = topic.lang.toProto
          )
        }
      )
    }

  override def store(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit = {
    ZActivity.run {
      val newsFeedArticlesZIO = ZIO.foreach(articles.articles.toList) { article =>
        for {
          itemId <- ZIO.randomWith(_.nextUUID)
        } yield {
          ContentFeedItem(
            id = itemId,
            topic = storeParams.topicId.fromProto,
            title = article.title,
            description = article.description,
            url = article.url,
            publishedAt = article.date.fromProto[LocalDateTime]
          )
        }
      }

      for {
        newsFeedArticles <- newsFeedArticlesZIO
        _                <- ZIO.logInfo(s"Storing articles topicId=${storeParams.topicId.fromProto}")
        _                <- newsFeedRepository.storeItems(newsFeedArticles)
      } yield ()
    }
  }
}
