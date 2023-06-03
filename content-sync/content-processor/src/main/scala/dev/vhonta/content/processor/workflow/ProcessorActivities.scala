package dev.vhonta.content.processor.workflow

import dev.vhonta.content.processor.proto._
import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationNewsApiDetails,
  ContentFeedIntegrationYoutubeDetails,
  ContentFeedItem,
  ContentFeedTopic,
  Subscriber
}
import dev.vhonta.content.repository.{
  ContentFeedIntegrationRepository,
  ContentFeedRecommendationRepository,
  ContentFeedRepository,
  SubscriberRepository
}
import dev.vhonta.content.{ContentFeedIntegrationDetails, ContentFeedRecommendation, ContentFeedRecommendationItem}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

import java.time.LocalDateTime
import java.util.UUID

case class SubscriberNotFoundException(subscriberId: UUID)
    extends Exception(s"Subscriber with id=$subscriberId not found")

case class IntegrationNotFound(id: Long) extends Exception(s"Integration with id=$id not found")

@activityInterface
trait ProcessorActivities {
  @activityMethod(name = "LoadSubscriberItems")
  def loadSubscriberWithItems(params: LoadSubscriberParams): SubscriberWithItems

  @throws[SubscriberNotFoundException]
  @throws[IntegrationNotFound]
  def loadAllSubscribersWithIntegrations(): AllSubscribersWithIntegrations

  def createRecommendations(params: SaveRecommendationsParams): Unit

  def checkRecommendationsExist(params: CheckRecommendationsExistParams): CheckRecommendationsExistResult
}

object ProcessorActivitiesImpl {
  val make: URLayer[
    SubscriberRepository
      with ContentFeedRepository
      with ContentFeedIntegrationRepository
      with ContentFeedRecommendationRepository
      with ContentFeedRecommendationEngine
      with ZActivityOptions[Any],
    ProcessorActivities
  ] =
    ZLayer.fromFunction(
      ProcessorActivitiesImpl(
        _: SubscriberRepository,
        _: ContentFeedRepository,
        _: ContentFeedIntegrationRepository,
        _: ContentFeedRecommendationRepository,
        _: ContentFeedRecommendationEngine
      )(_: ZActivityOptions[Any])
    )
}

case class ProcessorActivitiesImpl(
  subscriberRepository:                SubscriberRepository,
  contentFeedRepository:               ContentFeedRepository,
  contentFeedIntegrationRepository:    ContentFeedIntegrationRepository,
  contentFeedRecommendationRepository: ContentFeedRecommendationRepository,
  engine:                              ContentFeedRecommendationEngine
)(implicit options:                    ZActivityOptions[Any])
    extends ProcessorActivities {

  override def loadAllSubscribersWithIntegrations(): AllSubscribersWithIntegrations =
    ZActivity.run {
      for {
        _            <- ZIO.logInfo("Loading all subscribers with topics...")
        integrations <- contentFeedIntegrationRepository.list(subscribers = None)
      } yield AllSubscribersWithIntegrations(
        values = integrations.map(integration =>
          SubscriberWithIntegration(subscriberId = integration.subscriber, integrationId = integration.id)
        )
      )
    }

  override def loadSubscriberWithItems(params: LoadSubscriberParams): SubscriberWithItems =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Loading subscribers=${params.subscriberId.fromProto}")
        subscriber <- subscriberRepository
                        .findById(params.subscriberId.fromProto)
                        .someOrFail(SubscriberNotFoundException(params.subscriberId.fromProto))

        _ <- ZIO.logInfo(s"Loading integration=${params.integrationId}")
        integration <- contentFeedIntegrationRepository
                         .findById(params.integrationId)
                         .someOrFail(IntegrationNotFound(params.integrationId))

        _ <- ZIO.logInfo(s"Loading items integration=${params.integrationId} now=${params.forDate}")
        items <- contentFeedRepository.itemsForIntegration(
                   integrationId = params.integrationId,
                   now = params.forDate.fromProto[LocalDateTime]
                 )
      } yield SubscriberWithItems(
        subscriber = Subscriber(
          id = subscriber.id,
          registeredAt = subscriber.registeredAt
        ),
        integration = ContentFeedIntegration(
          id = integration.id,
          subscriber = integration.subscriber,
          // TODO: decouple conversion
          integration = integration.integration match {
            case ContentFeedIntegrationDetails.NewsApi(token) =>
              ContentFeedIntegrationNewsApiDetails(token)
            case ContentFeedIntegrationDetails.Youtube(accessToken, refreshToken, exchangedAt, expiresInSeconds) =>
              ContentFeedIntegrationYoutubeDetails(accessToken, refreshToken, exchangedAt, expiresInSeconds)
          }
        ),
        items = items.map { item =>
          ContentFeedItem(
            id = item.id,
            integration = integration.id,
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
               s"Creating recommendations subscriber=${params.subscriberWithIntegration.subscriberId.fromProto} " +
                 s"integration=${params.subscriberWithIntegration.integrationId} " +
                 s"num_items=${params.itemIds.size}"
             )
        recommendationId <- ZIO.randomWith(_.nextUUID)
        _ <- contentFeedRecommendationRepository.create(
               recommendation = ContentFeedRecommendation(
                 id = recommendationId,
                 owner = params.subscriberWithIntegration.subscriberId.fromProto,
                 integration = params.subscriberWithIntegration.integrationId.fromProto,
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
        _ <-
          ZIO.logInfo(
            s"Checking if recommendations exist subscriber=${params.subscriberWithIntegration.subscriberId.fromProto} " +
              s"integration=${params.subscriberWithIntegration.integrationId}"
          )
        exist <- contentFeedRecommendationRepository.existForDate(
                   params.subscriberWithIntegration.integrationId.fromProto,
                   params.forDate.fromProto[LocalDateTime].toLocalDate
                 )
      } yield CheckRecommendationsExistResult(exist)
    }
}
