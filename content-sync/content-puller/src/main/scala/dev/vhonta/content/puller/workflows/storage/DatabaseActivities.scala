package dev.vhonta.content.puller.workflows.storage

import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.proto.ContentFeedTopic
import dev.vhonta.content.puller.proto.{ContentFeedIntegrations, ListIntegrations, ListTopics, NewsSyncTopics}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

@activityInterface
trait DatabaseActivities {
  def loadIntegrations(list: ListIntegrations): ContentFeedIntegrations

  def loadNewsTopics(list: ListTopics): NewsSyncTopics
}

object DatabaseActivitiesImpl {
  val make: URLayer[
    ContentFeedRepository with ContentFeedIntegrationRepository with ZActivityOptions[Any],
    DatabaseActivities
  ] =
    ZLayer.fromFunction(
      DatabaseActivitiesImpl(_: ContentFeedRepository, _: ContentFeedIntegrationRepository)(
        _: ZActivityOptions[Any]
      )
    )
}

case class DatabaseActivitiesImpl(
  contentFeedRepository:  ContentFeedRepository,
  integrationsRepository: ContentFeedIntegrationRepository
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
}
