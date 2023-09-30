package dev.vhonta.content.puller.workflows.storage

import dev.vhonta.content.puller.proto.{
  NewsApiArticles,
  StoreArticlesParameters,
  StoreVideosParameters,
  YoutubeVideosList
}
import dev.vhonta.content.ContentType
import zio._
import zio.stream._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.net.URI
import java.time.LocalDateTime
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

  val make: ZLayer[ZActivityRunOptions[Any], Config.Error, DatalakeActivities] =
    ZLayer.fromZIO(ZIO.config(config)) >>>
      ZLayer.fromFunction(DatalakeActivitiesImpl(_: URI)(_: ZActivityRunOptions[Any]))
}

case class DatalakeActivitiesImpl(
  youtubeBaseUri:   URI
)(implicit options: ZActivityRunOptions[Any])
    extends DatalakeActivities {

  override def storeArticles(articles: NewsApiArticles, params: StoreArticlesParameters): Unit = {
    ZActivity.run {
      val contentFeedItemsStream = ZStream
        .fromIterable(articles.articles)
        .map { article =>
          ContentFeedItemParquetRow(
            topic = Some(params.topicId.fromProto),
            title = article.title,
            description = article.description,
            url = article.url,
            publishedAt = article.date.fromProto[LocalDateTime],
            contentType = ContentType.Text
          )
        }

      for {
        _ <- ZIO.logInfo(s"Storing articles topicId=${params.topicId.fromProto}")
        written <- writeToParquet(
                     contentFeedItemsStream = contentFeedItemsStream,
                     datalakeOutputDir = params.datalakeOutputDir,
                     integrationId = params.integrationId
                   )
        _ <- ZIO.logInfo(s"Written $written articles")
      } yield ()
    }
  }

  override def storeVideos(videos: YoutubeVideosList, params: StoreVideosParameters): Unit = {
    ZActivity.run {
      val contentFeedItemsStream = ZStream
        .fromIterable(videos.values)
        .map { video =>
          ContentFeedItemParquetRow(
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
        written <- writeToParquet(
                     contentFeedItemsStream = contentFeedItemsStream,
                     datalakeOutputDir = params.datalakeOutputDir,
                     integrationId = params.integrationId
                   )
        _ <- ZIO.logInfo(s"Written $written videos")
      } yield ()
    }
  }

  private def writeToParquet(
    contentFeedItemsStream: UStream[ContentFeedItemParquetRow],
    datalakeOutputDir:      String,
    integrationId:          Long
  ): IO[IOException, Long] = {
    for {
      now <- ZIO.clockWith(_.localDateTime)
      written <- contentFeedItemsStream
                   .grouped(100)
                   .mapZIO(
                     ParquetWritingFacade.write(
                       now = now.toLocalDate,
                       datalakeOutputDir = datalakeOutputDir,
                       integrationId = integrationId
                     )
                   )
                   .runCount
    } yield written
  }
}
