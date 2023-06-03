package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationNewsApiDetails,
  ContentFeedIntegrationType,
  ContentFeedIntegrationYoutubeDetails
}
import dev.vhonta.content.puller.proto._
import dev.vhonta.content.puller.workflows.DatabaseActivities
import zio._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

object MockDatabaseActivities {
  val make: URLayer[ZActivityOptions[Any], DatabaseActivities] =
    ZLayer.fromFunction(new MockDatabaseActivities()(_: ZActivityOptions[Any]))
}

class MockDatabaseActivities(implicit options: ZActivityOptions[Any]) extends DatabaseActivities {
  override def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations = {
    ZActivity.run {
      for {
        _          <- ZIO.logInfo(s"Generating mock integration type=${list.integrationType}")
        id         <- ZIO.randomWith(_.nextLong)
        subscriber <- ZIO.randomWith(_.nextUUID)
        now        <- ZIO.clockWith(_.instant)
      } yield {
        ContentFeedIntegrations(
          integrations = Seq(
            ContentFeedIntegration(
              id,
              subscriber,
              integration = list.integrationType match {
                case ContentFeedIntegrationType.news_api =>
                  ContentFeedIntegrationNewsApiDetails("token")
                case ContentFeedIntegrationType.youtube =>
                  ContentFeedIntegrationYoutubeDetails(
                    accessToken = "accessToken",
                    refreshToken = "refreshToken",
                    exchangedAt = now,
                    expiresInSeconds = 7200
                  )
              }
            )
          )
        )
      }
    }
  }

  // TODO: make meaningful
  override def loadNewsTopics(list: ListTopics): NewsSyncTopics =
    NewsSyncTopics(Nil)

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
