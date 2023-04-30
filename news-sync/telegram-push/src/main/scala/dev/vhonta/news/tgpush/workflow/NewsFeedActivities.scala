package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.ProtoConverters._
import dev.vhonta.news.proto.{NewsFeedArticle, NewsFeedRecommendationView}
import dev.vhonta.news.repository.{NewsFeedRecommendationRepository, NewsFeedRepository, ReaderRepository}
import dev.vhonta.news.tgpush.proto._
import dev.vhonta.news.{NewsFeedTopic, NewsTopicLanguage, proto}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

import java.time.{LocalDate, LocalTime}

@activityInterface
trait NewsFeedActivities {
  def listTopics(params: ListTopicsParams): ListTopicsResult

  def createTopic(params: CreateTopicParams): Unit

  def listAllReaders(params: ListAllReadersParams): ListAllReadersResult

  def listRecommendations(params: ListRecommendationsParams): ListRecommendationsResult
}

object NewsFeedActivitiesImpl {
  val make: URLayer[
    NewsFeedRepository with ReaderRepository with NewsFeedRecommendationRepository with ZActivityOptions[Any],
    NewsFeedActivities
  ] =
    ZLayer.fromFunction(
      NewsFeedActivitiesImpl(
        _: NewsFeedRepository,
        _: ReaderRepository,
        _: NewsFeedRecommendationRepository
      )(_: ZActivityOptions[Any])
    )
}

case class NewsFeedActivitiesImpl(
  newsFeedRepository:               NewsFeedRepository,
  readerRepository:                 ReaderRepository,
  newsFeedRecommendationRepository: NewsFeedRecommendationRepository
)(implicit options:                 ZActivityOptions[Any])
    extends NewsFeedActivities {
  override def listTopics(params: ListTopicsParams): ListTopicsResult = {
    ZActivity.run {
      for {
        _      <- ZIO.logInfo(s"Listing topics reader=${params.reader.fromProto}")
        topics <- newsFeedRepository.listTopics(readers = Some(Set(params.reader.fromProto)))
      } yield {
        ListTopicsResult(
          topics = topics.map { topic =>
            proto.NewsFeedTopic(
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
        _ <- newsFeedRepository.createTopic(
               NewsFeedTopic(
                 id = topicId,
                 owner = params.reader.fromProto,
                 topic = params.topic,
                 // TODO: make configurable
                 lang = NewsTopicLanguage.English
               )
             )
      } yield ()
    }

  override def listAllReaders(params: ListAllReadersParams): ListAllReadersResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo("List all readers...")
        readers <- readerRepository.listAllForPublish(
                     currentTime = params.now.fromProto[LocalTime],
                     deltaMinutes = params.deltaMinutes
                   )
      } yield ListAllReadersResult(
        values = readers.map(readerWithSettings =>
          proto.ReaderWithSettings(
            reader = proto.Reader(
              id = readerWithSettings.reader.id,
              registeredAt = readerWithSettings.reader.registeredAt
            ),
            settings = proto.ReaderSettings(
              reader = readerWithSettings.settings.reader,
              modifiedAt = readerWithSettings.settings.modifiedAt,
              timezone = readerWithSettings.settings.timezone,
              publishAt = readerWithSettings.settings.publishAt
            )
          )
        )
      )
    }

  override def listRecommendations(params: ListRecommendationsParams): ListRecommendationsResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(
               s"List recommendations reader=${params.readerWithSettings.reader.id.fromProto} date=${params.date}"
             )
        topics <- newsFeedRepository.listTopics(readers = Some(Set(params.readerWithSettings.reader.id.fromProto)))
        recommendations <- ZIO.foreachPar(topics) { topic =>
                             newsFeedRecommendationRepository.getForDate(
                               topicId = topic.id,
                               date = params.date.fromProto[LocalDate]
                             )
                           }
      } yield ListRecommendationsResult(
        results = recommendations.flatten.map { recommendation =>
          NewsFeedRecommendationView(
            topicId = recommendation.topicId,
            topic = recommendation.topic,
            date = recommendation.date,
            articles = recommendation.articles.map { article =>
              NewsFeedArticle(
                id = article.id,
                topic = article.topic,
                title = article.title,
                description = article.description,
                url = article.url,
                publishedAt = article.publishedAt
              )
            }
          )
        }
      )
    }
}
