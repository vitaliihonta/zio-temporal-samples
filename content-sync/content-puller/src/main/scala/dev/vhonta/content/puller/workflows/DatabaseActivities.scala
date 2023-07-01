package dev.vhonta.content.puller.workflows

import dev.vhonta.content.{ContentFeedItem, ContentType}
import dev.vhonta.content.proto.ContentFeedTopic
import dev.vhonta.content.puller.proto.{
  ContentFeedIntegrations,
  ListIntegrations,
  ListTopics,
  NewsApiArticles,
  NewsSyncTopics,
  StoreArticlesParameters,
  StoreVideosParameters,
  YoutubeVideosList
}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository}
import zio._
import zio.stream._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import java.net.URI
import java.time.LocalDateTime
import dev.vhonta.content.ProtoConverters._

@activityInterface
trait DatabaseActivities {
  def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations

  def loadNewsTopics(list: ListTopics): NewsSyncTopics

  def storeArticles(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit

  def storeVideos(videos: YoutubeVideosList, params: StoreVideosParameters): Unit
}

object DatabaseActivitiesImpl {
  private val config = Config
    .uri("youtube_base_url")
    .withDefault(new URI("https://www.youtube.com/watch?v="))
    .nested("database_activity")

  val make: ZLayer[
    ContentFeedRepository with ContentFeedIntegrationRepository with ZActivityOptions[Any],
    Config.Error,
    DatabaseActivities
  ] =
    ZLayer.fromZIO(ZIO.config(config)) >>> ZLayer.fromFunction(
      DatabaseActivitiesImpl(_: ContentFeedRepository, _: ContentFeedIntegrationRepository, _: URI)(
        _: ZActivityOptions[Any]
      )
    )
}

case class DatabaseActivitiesImpl(
  contentFeedRepository:  ContentFeedRepository,
  integrationsRepository: ContentFeedIntegrationRepository,
  youtubeBaseUri:         URI
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
        integrations.map(_.toProto)
      )
    }

  override def loadNewsTopics(list: ListTopics): NewsSyncTopics =
    ZActivity.run {
      for {
        _      <- ZIO.logInfo("Loading news topics...")
        topics <- contentFeedRepository.listTopics(subscribers = Some(list.subscribers.map(_.fromProto).toSet))
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

  override def storeArticles(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit = {
    ZActivity.run {
      // TODO: implement
      val contentFeedItemsStream = ZStream
        .fromIterable(articles.articles)
        .map { article =>
          ContentFeedItem(
            integration = storeParams.integrationId,
            topic = Some(storeParams.topicId.fromProto),
            title = article.title,
            description = article.description,
            url = article.url,
            publishedAt = article.date.fromProto[LocalDateTime],
            contentType = ContentType.Text
          )
        }

      for {
        _ <- ZIO.logInfo(s"Storing articles topicId=${storeParams.topicId.fromProto}")
        _ <- ZIO.fail(new NotImplementedError())
      } yield ()
    }
  }

  override def storeVideos(videos: YoutubeVideosList, params: StoreVideosParameters): Unit = {
    ZActivity.run {
      // TODO: implement
      val contentFeedItemsStream = ZStream
        .fromIterable(videos.values)
        .map { video =>
          ContentFeedItem(
            integration = params.integrationId,
            topic = None,
            title = video.title,
            description = video.description,
            url = youtubeBaseUri.toString + video.videoId,
            publishedAt = video.publishedAt.fromProto[LocalDateTime],
            contentType = ContentType.Video
          )
        }

      for {
        _ <- ZIO.logInfo("Storing videos")
        _ <- ZIO.fail(new NotImplementedError())
      } yield ()
    }
  }
}
