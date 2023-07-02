package dev.vhonta.content.puller.workflows.storage

import dev.vhonta.content.puller.proto.{
  NewsApiArticles,
  StoreArticlesParameters,
  StoreVideosParameters,
  YoutubeVideosList
}
import dev.vhonta.content.{ContentFeedIntegrationType, ContentFeedItem, ContentType}
import zio._
import zio.stream._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.net.URI
import java.time.LocalDateTime
import ParquetSerializers._
import com.github.mjakubowski84.parquet4s.ParquetWriter
import java.io.IOException

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

  override def storeArticles(articles: NewsApiArticles, params: StoreArticlesParameters): Unit = {
    ZActivity.run {
      val contentFeedItemsStream = ZStream
        .fromIterable(articles.articles)
        .map { article =>
          ContentFeedItem(
            integration = params.integrationId,
            topic = Some(params.topicId.fromProto),
            title = article.title,
            description = article.description,
            url = article.url,
            publishedAt = article.date.fromProto[LocalDateTime],
            contentType = ContentType.Text
          )
        }

      for {
        _       <- ZIO.logInfo(s"Storing articles topicId=${params.topicId.fromProto}")
        written <- writeToParquet(contentFeedItemsStream, params.datalakeOutputDir, ContentFeedIntegrationType.NewsApi)
        _       <- ZIO.logInfo(s"Written $written articles")
      } yield ()
    }
  }

  override def storeVideos(videos: YoutubeVideosList, params: StoreVideosParameters): Unit = {
    ZActivity.run {
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
        _       <- ZIO.logInfo("Storing videos")
        written <- writeToParquet(contentFeedItemsStream, params.datalakeOutputDir, ContentFeedIntegrationType.Youtube)
        _       <- ZIO.logInfo(s"Written $written videos")
      } yield ()
    }
  }

  private def writeToParquet(
    contentFeedItemsStream: UStream[ContentFeedItem],
    datalakeOutputDir:      String,
    integrationType:        ContentFeedIntegrationType
  ): IO[IOException, Long] = {
    for {
      now <- ZIO.clockWith(_.instant)
      written <- contentFeedItemsStream
                   .grouped(20)
                   .mapZIO(
                     ParquetWritingFacade.write(
                       ParquetWriter.of[ContentFeedItem],
                       now,
                       datalakeOutputDir,
                       integrationType
                     )
                   )
                   .runCount
    } yield written
  }
}
