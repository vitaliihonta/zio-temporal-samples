package dev.vhonta.content.tgbot.workflow.common

import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.proto._
import dev.vhonta.content.repository.{
  ContentFeedIntegrationRepository,
  ContentFeedRecommendationRepository,
  ContentFeedRepository,
  SubscriberRepository
}
import dev.vhonta.content.tgbot.proto._
import dev.vhonta.content.{ContentFeedIntegrationDetails, ContentFeedTopic, proto}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.{LocalDate, LocalTime}

@activityInterface
trait ContentFeedActivities {
  def listTopics(params: ListTopicsParams): ListTopicsResult

  def createTopic(params: CreateTopicParams): Unit

  def listAllSubscribers(params: ListAllSubscribersParams): ListAllSubscribersResult

  def listRecommendations(params: ListRecommendationsParams): ListRecommendationsResult
}

object ContentFeedActivitiesImpl {
  val make: URLayer[
    ContentFeedRepository
      with SubscriberRepository
      with ContentFeedIntegrationRepository
      with ContentFeedRecommendationRepository
      with ZActivityOptions[Any],
    ContentFeedActivities
  ] =
    ZLayer.fromFunction(
      ContentFeedActivitiesImpl(
        _: ContentFeedRepository,
        _: SubscriberRepository,
        _: ContentFeedIntegrationRepository,
        _: ContentFeedRecommendationRepository
      )(_: ZActivityOptions[Any])
    )
}

case class ContentFeedActivitiesImpl(
  contentFeedRepository:               ContentFeedRepository,
  subscriberRepository:                SubscriberRepository,
  contentFeedIntegrationRepository:    ContentFeedIntegrationRepository,
  contentFeedRecommendationRepository: ContentFeedRecommendationRepository
)(implicit options:                    ZActivityOptions[Any])
    extends ContentFeedActivities {

  override def listTopics(params: ListTopicsParams): ListTopicsResult = {
    ZActivity.run {
      for {
        _      <- ZIO.logInfo(s"Listing topics subscriber=${params.subscriber.fromProto}")
        topics <- contentFeedRepository.listTopics(subscribers = Some(Set(params.subscriber.fromProto)))
      } yield {
        ListTopicsResult(
          topics = topics.map { topic =>
            proto.ContentFeedTopic(
              id = topic.id,
              owner = topic.owner,
              topic = topic.topic,
              lang = topic.lang
            )
          }
        )
      }
    }
  }

  override def createTopic(params: CreateTopicParams): Unit =
    ZActivity.run {
      for {
        _       <- ZIO.logInfo(s"Creating topic")
        topicId <- ZIO.randomWith(_.nextUUID)
        _ <- contentFeedRepository.createTopic(
               ContentFeedTopic(
                 id = topicId,
                 owner = params.subscriber.fromProto,
                 topic = params.topic,
                 lang = params.lang.fromProto
               )
             )
      } yield ()
    }

  override def listAllSubscribers(params: ListAllSubscribersParams): ListAllSubscribersResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo("List all subscribers...")
        subscribers <- subscriberRepository.listAllForPublish(
                         currentTime = params.now.fromProto[LocalTime],
                         deltaMinutes = params.deltaMinutes
                       )
      } yield ListAllSubscribersResult(
        values = subscribers.map(subscriberWithSettings =>
          proto.SubscriberWithSettings(
            subscriber = proto.Subscriber(
              id = subscriberWithSettings.subscriber.id,
              registeredAt = subscriberWithSettings.subscriber.registeredAt
            ),
            settings = proto.SubscriberSettings(
              subscriber = subscriberWithSettings.settings.subscriber,
              modifiedAt = subscriberWithSettings.settings.modifiedAt,
              timezone = subscriberWithSettings.settings.timezone,
              publishAt = subscriberWithSettings.settings.publishAt
            )
          )
        )
      )
    }

  override def listRecommendations(params: ListRecommendationsParams): ListRecommendationsResult =
    ZActivity.run {
      for {
        _ <-
          ZIO.logInfo(
            s"List recommendations subscriber=${params.subscriberWithSettings.subscriber.id.fromProto} date=${params.date}"
          )
        integrations <- contentFeedIntegrationRepository.list(
                          subscribers = Some(Set(params.subscriberWithSettings.subscriber.id.fromProto))
                        )
        recommendations <- ZIO.foreachPar(integrations) { integration =>
                             contentFeedRecommendationRepository.getForDate(
                               integrationId = integration.id,
                               date = params.date.fromProto[LocalDate]
                             )
                           }
      } yield ListRecommendationsResult(
        results = recommendations.flatten.map { recommendation =>
          ContentFeedRecommendationView(
            id = recommendation.id,
            integration = ContentFeedIntegration(
              id = recommendation.integration.id,
              subscriber = recommendation.integration.subscriber,
              integration = recommendation.integration.integration match {
                case ContentFeedIntegrationDetails.NewsApi(token) =>
                  ContentFeedIntegrationNewsApiDetails(token)
                case ContentFeedIntegrationDetails.Youtube(accessToken, refreshToken, exchangedAt, expiresInSeconds) =>
                  ContentFeedIntegrationYoutubeDetails(
                    accessToken,
                    refreshToken,
                    exchangedAt,
                    expiresInSeconds
                  )
              }
            ),
            date = recommendation.date,
            items = recommendation.items.map { item =>
              ContentFeedRecommendationItem(
                recommendation = recommendation.id,
                topic = item.topic,
                title = item.title,
                description = item.description,
                url = item.url,
                contentType = item.contentType
              )
            }
          )
        }
      )
    }
}
