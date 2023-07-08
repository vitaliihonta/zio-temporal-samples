package dev.vhonta.content.puller.workflows.youtube.mock

import dev.vhonta.content.puller.proto.{
  NewsApiArticles,
  StoreArticlesParameters,
  StoreVideosParameters,
  YoutubeVideosList
}
import dev.vhonta.content.puller.workflows.storage.DatalakeActivities
import zio._
import zio.temporal.activity.{ZActivity, ZActivityOptions}

case class MockDatalakeActivities()(implicit options: ZActivityOptions[Any]) extends DatalakeActivities {
  override def storeArticles(articles: NewsApiArticles, storeParams: StoreArticlesParameters): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Stored ${articles.articles.size} articles")
    }
  }

  override def storeVideos(videos: YoutubeVideosList, params: StoreVideosParameters): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Stored ${videos.values.size} videos")
    }
  }
}
