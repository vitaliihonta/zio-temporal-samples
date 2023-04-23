package dev.vhonta.news.puller.workflows

import dev.vhonta.news.NewsFeedArticle
import dev.vhonta.news.puller._
import dev.vhonta.news.repository.NewsFeedRepository
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime

@activityInterface
trait DatabaseActivities {
  def loadNewsTopics: NewsSyncTopics
  def store(articles: Articles, storeParams: StoreArticlesParameters): Unit
}

object DatabaseActivitiesImpl {
  val make: URLayer[NewsFeedRepository with ZActivityOptions[Any], DatabaseActivities] =
    ZLayer.fromFunction(DatabaseActivitiesImpl(_: NewsFeedRepository)(_: ZActivityOptions[Any]))
}

case class DatabaseActivitiesImpl(newsFeedRepository: NewsFeedRepository)(implicit options: ZActivityOptions[Any])
    extends DatabaseActivities {

  override def loadNewsTopics: NewsSyncTopics =
    ZActivity.run {
      for {
        _      <- ZIO.logInfo("Loading news topics...")
        topics <- newsFeedRepository.listAllTopics
      } yield NewsSyncTopics(
        topics = topics.map { topic =>
          NewsSyncTopic(
            id = topic.id.toProto,
            topic = topic.topic,
            language = topic.lang.toProto
          )
        }
      )
    }

  override def store(articles: Articles, storeParams: StoreArticlesParameters): Unit = {
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
