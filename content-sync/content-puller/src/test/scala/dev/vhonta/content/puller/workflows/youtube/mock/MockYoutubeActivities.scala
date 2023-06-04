package dev.vhonta.content.puller.workflows.youtube.mock

import dev.vhonta.content.puller.proto.{FetchVideosParams, FetchVideosResult, YoutubeSearchResult}
import dev.vhonta.content.puller.workflows.youtube.YoutubeActivities
import zio._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

case class MockYoutubeActivities(videosCount: Int)(implicit options: ZActivityOptions[Any]) extends YoutubeActivities {
  override def fetchVideos(params: FetchVideosParams): FetchVideosResult = {
    ZActivity.run {
      for {
        now  <- ZIO.clockWith(_.instant)
        uuid <- ZIO.randomWith(_.nextUUID)
        _    <- ZIO.logInfo(s"Producing $videosCount mock videos")
        videos <- ZIO.foreach(List.range(1, videosCount + 1)) { num =>
                    ZIO.randomWith(_.nextString(10)).map { str =>
                      YoutubeSearchResult(
                        videoId = s"$uuid-$num",
                        title = s"Video $str",
                        description = None,
                        publishedAt = now.toProto
                      )
                    }
                  }
      } yield {
        FetchVideosResult(videos)
      }
    }
  }
}
