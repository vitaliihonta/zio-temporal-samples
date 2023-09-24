package dev.vhonta.content.puller.workflows.storage

import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.PullerState
import dev.vhonta.content.proto.ContentFeedTopic
import dev.vhonta.content.puller.proto.{
  ContentFeedIntegrations,
  ListIntegrations,
  ListTopics,
  LoadPullerStatesParams,
  LoadPullerStatesResult,
  NewsSyncTopics,
  UpsertPullerStateParams,
  UpsertPullerStateResult
}
import dev.vhonta.content.puller.proto
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository, PullerStateRepository}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

@activityInterface
trait DatabaseActivities {
  def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations

  def loadNewsTopics(list: ListTopics): NewsSyncTopics

  def loadAllPullerStates(params: LoadPullerStatesParams): LoadPullerStatesResult

  def upsertPullerState(params: UpsertPullerStateParams): UpsertPullerStateResult
}

object DatabaseActivitiesImpl {
  val make: URLayer[
    ContentFeedRepository
      with ContentFeedIntegrationRepository
      with PullerStateRepository
      with ZActivityRunOptions[Any],
    DatabaseActivities
  ] =
    ZLayer.fromFunction(
      DatabaseActivitiesImpl(_: ContentFeedRepository, _: ContentFeedIntegrationRepository, _: PullerStateRepository)(
        _: ZActivityRunOptions[Any]
      )
    )
}

case class DatabaseActivitiesImpl(
  contentFeedRepository:  ContentFeedRepository,
  integrationsRepository: ContentFeedIntegrationRepository,
  pullerStateRepository:  PullerStateRepository
)(implicit options:       ZActivityRunOptions[Any])
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

  override def loadAllPullerStates(params: LoadPullerStatesParams): LoadPullerStatesResult = {
    ZActivity.run {
      for {
        _     <- ZIO.logInfo(s"Looking for puller states integrationType=${params.integrationType}")
        found <- pullerStateRepository.load(params.integrationType.fromProto)
      } yield {
        LoadPullerStatesResult(
          states = found.map(_.toProto)
        )
      }
    }
  }

  override def upsertPullerState(params: UpsertPullerStateParams): UpsertPullerStateResult = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo("Upserting puller states")
        _ <- pullerStateRepository.upsertStates(
               params.states.view.flatMap(PullerState.safeFromProto).toList
             )
      } yield {
        UpsertPullerStateResult()
      }
    }
  }

}
