package dev.vhonta.content.puller.workflows

import zio._
import zio.stream._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.{ContentFeedItem, ContentType}
import dev.vhonta.content.puller.proto.{
  NewsApiArticles,
  StoreArticlesParameters,
  StoreVideosParameters,
  YoutubeVideosList
}

import java.net.URI
import java.time.LocalDateTime

@activityInterface
trait DatalakeActivities {
  def storeArticles(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit

  def storeVideos(videos: YoutubeVideosList, params: StoreVideosParameters): Unit
}

object DatalakeActivitiesImpl {
  private val config = Config
    .uri("youtube_base_url")
    .withDefault(new URI("https://www.youtube.com/watch?v="))
    .nested("database_activity")

  val make: ZLayer[ZActivityOptions[Any], Config.Error, DatalakeActivities] =
    ZLayer.fromZIO(ZIO.config(config)) >>>
      ZLayer.fromFunction(DatalakeActivitiesImpl(_: URI)(_: ZActivityOptions[Any]))
}

case class DatalakeActivitiesImpl(youtubeBaseUri: URI)(implicit options: ZActivityOptions[Any])
    extends DatalakeActivities {

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
