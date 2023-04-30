package dev.vhonta.news.puller.workflows

import dev.vhonta.news.{NewsFeedArticle, NewsFeedIntegrationDetails}
import dev.vhonta.news.proto.{NewsFeedIntegration, NewsFeedIntegrationNewsApiDetails, NewsFeedTopic}
import dev.vhonta.news.puller.proto.{
  ListIntegrations,
  ListTopics,
  NewsApiArticles,
  NewsFeedIntegrations,
  NewsSyncTopics,
  StoreArticlesParameters
}
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, NewsFeedRepository}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

import java.time.LocalDateTime

@activityInterface
trait DatabaseActivities {
  def loadIntegrations(list: ListIntegrations): NewsFeedIntegrations

  def loadNewsTopics(list: ListTopics): NewsSyncTopics

  def store(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit
}

object DatabaseActivitiesImpl {
  val make
    : URLayer[NewsFeedRepository with NewsFeedIntegrationRepository with ZActivityOptions[Any], DatabaseActivities] =
    ZLayer.fromFunction(
      DatabaseActivitiesImpl(_: NewsFeedRepository, _: NewsFeedIntegrationRepository)(_: ZActivityOptions[Any])
    )
}

case class DatabaseActivitiesImpl(
  newsFeedRepository:     NewsFeedRepository,
  integrationsRepository: NewsFeedIntegrationRepository
)(implicit options:       ZActivityOptions[Any])
    extends DatabaseActivities {

  override def loadIntegrations(list: ListIntegrations): NewsFeedIntegrations =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Loading integrations type=${list.integrationType}")
        integrations <- integrationsRepository.findByType(
                          list.integrationType.fromProto
                        )
      } yield NewsFeedIntegrations(
        integrations.map(integration =>
          NewsFeedIntegration(
            id = integration.id,
            readerId = integration.reader,
            // TODO: decouple conversion
            integration = integration.integration match {
              case NewsFeedIntegrationDetails.NewsApi(token) =>
                NewsFeedIntegration.Integration.NewsApi(
                  NewsFeedIntegrationNewsApiDetails(token)
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
        topics <- newsFeedRepository.listTopics(readers = Some(list.readers.map(_.fromProto).toSet))
      } yield NewsSyncTopics(
        topics = topics.map { topic =>
          NewsFeedTopic(
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
          articleId <- ZIO.randomWith(_.nextUUID)
        } yield {
          NewsFeedArticle(
            id = articleId,
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
        _                <- newsFeedRepository.storeArticles(newsFeedArticles)
      } yield ()
    }
  }
}
