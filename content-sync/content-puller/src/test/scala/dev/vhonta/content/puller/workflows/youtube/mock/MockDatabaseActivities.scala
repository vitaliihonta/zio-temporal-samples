package dev.vhonta.content.puller.workflows.youtube.mock

import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationNewsApiDetails,
  ContentFeedIntegrationType,
  ContentFeedIntegrationYoutubeDetails,
  NewsApiIntegrationState,
  YoutubePullerIntegrationState
}
import dev.vhonta.content.puller.proto._
import dev.vhonta.content.puller.workflows.storage.DatabaseActivities
import zio._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.Instant
import java.util.UUID

case class MockDatabaseActivities()(implicit options: ZActivityRunOptions[Any]) extends DatabaseActivities {
  private val integrations: Map[ContentFeedIntegrationType, ContentFeedIntegration] =
    ContentFeedIntegrationType.values.view.map { integrationType =>
      integrationType -> ContentFeedIntegration(
        id = scala.util.Random.nextLong(),
        subscriber = UUID.randomUUID(),
        integration = integrationType match {
          case ContentFeedIntegrationType.news_api =>
            ContentFeedIntegrationNewsApiDetails("token")
          case ContentFeedIntegrationType.youtube =>
            ContentFeedIntegrationYoutubeDetails(
              accessToken = "accessToken",
              refreshToken = "refreshToken",
              exchangedAt = Instant.now(),
              expiresInSeconds = 7200
            )
        }
      )
    }.toMap

  override def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Generating mock integration type=${list.integrationType}")
      } yield {
        ContentFeedIntegrations(
          integrations = integrations.get(list.integrationType).toList
        )
      }
    }
  }

  /*NOTE: override in case of usages in tests*/
  override def loadNewsTopics(list: ListTopics): NewsSyncTopics =
    NewsSyncTopics(Nil)

  override def loadAllPullerStates(params: LoadPullerStatesParams): LoadPullerStatesResult =
    ZActivity.run {
      for {
        _   <- ZIO.logInfo(s"Getting mock integration state type=${params.integrationType}")
        now <- Clock.instant
        lastProcessedAt = now.minusSeconds(60 * 15)
      } yield {
        LoadPullerStatesResult(
          states = integrations.get(params.integrationType).toList.flatMap { integration =>
            params.integrationType match {
              case ContentFeedIntegrationType.news_api =>
                Some(
                  NewsApiIntegrationState(integration.id, lastProcessedAt)
                )
              case ContentFeedIntegrationType.youtube =>
                Some(
                  YoutubePullerIntegrationState(integration.id, lastProcessedAt)
                )
              case _ => None
            }
          }
        )
      }
    }

  override def upsertPullerState(params: UpsertPullerStateParams): UpsertPullerStateResult = {
    ZActivity.run {
      ZIO
        .logInfo(s"Upserting mock state=${params.states}")
        .as(UpsertPullerStateResult())
    }
  }
}
