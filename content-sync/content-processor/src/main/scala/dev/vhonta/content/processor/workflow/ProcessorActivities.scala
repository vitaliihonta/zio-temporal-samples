package dev.vhonta.content.processor.workflow

import dev.vhonta.content.processor.proto._
import dev.vhonta.content.proto.{ContentFeedItem, ContentFeedTopic, Subscriber}
import dev.vhonta.content.repository.{ContentFeedRecommendationRepository, ContentFeedRepository, SubscriberRepository}
import dev.vhonta.content.{ContentFeedRecommendation, ContentFeedRecommendationItem}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime
import java.util.UUID

case class SubscriberNotFoundException(subscriberId: UUID)
    extends Exception(s"Subscriber with id=$subscriberId not found")

case class TopicNotFoundException(topicId: UUID) extends Exception(s"Topic with id=$topicId not found")

@activityInterface
trait ProcessorActivities {
  @activityMethod(name = "LoadSubscriberItems")
  def loadSubscriberWithItems(params: LoadSubscriberParams): SubscriberWithItems

  @throws[SubscriberNotFoundException]
  @throws[TopicNotFoundException]
  def loadAllSubscribersWithTopics(): AllSubscribersWithTopics

  def createRecommendations(params: SaveRecommendationsParams): Unit

  def checkRecommendationsExist(params: CheckRecommendationsExistParams): CheckRecommendationsExistResult
}

object ProcessorActivitiesImpl {
  val make: URLayer[
    SubscriberRepository
      with ContentFeedRepository
      with ContentFeedRecommendationRepository
      with ContentFeedRecommendationEngine
      with ZActivityOptions[Any],
    ProcessorActivities
  ] =
    ZLayer.fromFunction(
      ProcessorActivitiesImpl(
        _: SubscriberRepository,
        _: ContentFeedRepository,
        _: ContentFeedRecommendationRepository,
        _: ContentFeedRecommendationEngine
      )(_: ZActivityOptions[Any])
    )
}

case class ProcessorActivitiesImpl(
  subscriberRepository:                SubscriberRepository,
  contentFeedRepository:               ContentFeedRepository,
  contentFeedRecommendationRepository: ContentFeedRecommendationRepository,
  engine:                              ContentFeedRecommendationEngine
)(implicit options:                    ZActivityOptions[Any])
    extends ProcessorActivities {

  override def loadAllSubscribersWithTopics(): AllSubscribersWithTopics =
    ZActivity.run {
      for {
        _      <- ZIO.logInfo("Loading all subscribers with topics...")
        topics <- contentFeedRepository.listTopics(subscribers = None)
      } yield AllSubscribersWithTopics(
        subscribersWithTopics = topics.map(topic => SubscriberWithTopic(subscriberId = topic.owner, topicId = topic.id))
      )
    }

  override def loadSubscriberWithItems(params: LoadSubscriberParams): SubscriberWithItems =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Loading subscribers=${params.subscriberId.fromProto}")
        subscriber <- subscriberRepository
                        .findById(params.subscriberId.fromProto)
                        .someOrFail(SubscriberNotFoundException(params.subscriberId.fromProto))

        _ <- ZIO.logInfo(s"Loading topic=${params.topicId.fromProto}")
        topic <- contentFeedRepository
                   .findTopicById(params.topicId.fromProto)
                   .someOrFail(TopicNotFoundException(params.topicId.fromProto))

        _ <- ZIO.logInfo(s"Loading items topic=${params.topicId.fromProto} now=${params.forDate}")
        items <- contentFeedRepository.itemsForTopic(
                   topicId = params.topicId.fromProto,
                   now = params.forDate.fromProto[LocalDateTime]
                 )
      } yield SubscriberWithItems(
        subscriber = Subscriber(
          id = subscriber.id,
          registeredAt = subscriber.registeredAt
        ),
        topic = ContentFeedTopic(
          id = topic.id,
          owner = topic.owner,
          topic = topic.topic,
          lang = topic.lang
        ),
        items = items.map { item =>
          ContentFeedItem(
            id = item.id,
            topic = item.topic,
            title = item.title,
            description = item.description,
            url = item.url,
            publishedAt = item.publishedAt,
            contentType = item.contentType
          )
        }
      )
    }

  override def createRecommendations(params: SaveRecommendationsParams): Unit =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(
               s"Creating recommendations subscriber=${params.subscriberWithTopic.subscriberId.fromProto} " +
                 s"topic=${params.subscriberWithTopic.subscriberId} " +
                 s"num_items=${params.itemIds.size}"
             )
        recommendationId <- ZIO.randomWith(_.nextUUID)
        _ <- contentFeedRecommendationRepository.create(
               recommendation = ContentFeedRecommendation(
                 id = recommendationId,
                 owner = params.subscriberWithTopic.subscriberId.fromProto,
                 topic = params.subscriberWithTopic.topicId.fromProto,
                 forDate = params.forDate.fromProto[LocalDateTime].toLocalDate
               ),
               items = params.itemIds.view.map { itemId =>
                 ContentFeedRecommendationItem(
                   recommendation = recommendationId,
                   item = itemId.fromProto
                 )
               }.toList
             )
      } yield ()
    }

  override def checkRecommendationsExist(params: CheckRecommendationsExistParams): CheckRecommendationsExistResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(
               s"Checking if recommendations exist subscriber=${params.subscriberWithTopic.subscriberId.fromProto} " +
                 s"topic=${params.subscriberWithTopic.subscriberId}"
             )
        exist <- contentFeedRecommendationRepository.existForDate(
                   params.subscriberWithTopic.topicId.fromProto,
                   params.forDate.fromProto[LocalDateTime].toLocalDate
                 )
      } yield CheckRecommendationsExistResult(exist)
    }
}
