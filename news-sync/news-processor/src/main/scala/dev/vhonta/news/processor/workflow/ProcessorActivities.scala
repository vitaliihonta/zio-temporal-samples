package dev.vhonta.news.processor.workflow

import zio.temporal._
import zio.temporal.activity._
import dev.vhonta.news.processor.NewsFeedRecommendationEngine
import dev.vhonta.news.repository.{NewsFeedRecommendationRepository, NewsFeedRepository, ReaderRepository}
import dev.vhonta.news.processor.proto.{LoadReaderParams, ReaderWithArticles}
import dev.vhonta.news.proto.{NewsFeedArticle, NewsFeedTopic, Reader}
import zio._
import zio.temporal.protobuf.syntax._

import java.time.LocalDateTime
import java.util.UUID

@activityInterface
trait ProcessorActivities {
  @activityMethod(name = "LoadReadersArticles")
  def loadReaderWithArticles(params: LoadReaderParams): ReaderWithArticles
}

case class ReaderNotFoundException(readerId: UUID) extends Exception(s"Reader with id=$readerId not found")
case class TopicNotFoundException(topicId: UUID)   extends Exception(s"Topic with id=$topicId not found")

object ProcessorActivitiesImpl {
  val make: URLayer[
    ReaderRepository
      with NewsFeedRepository
      with NewsFeedRecommendationRepository
      with NewsFeedRecommendationEngine
      with ZActivityOptions[Any],
    ProcessorActivities
  ] =
    ZLayer.fromFunction(
      ProcessorActivitiesImpl(
        _: ReaderRepository,
        _: NewsFeedRepository,
        _: NewsFeedRecommendationRepository,
        _: NewsFeedRecommendationEngine
      )(_: ZActivityOptions[Any])
    )
}

case class ProcessorActivitiesImpl(
  readerRepository:                 ReaderRepository,
  newsFeedRepository:               NewsFeedRepository,
  newsFeedRecommendationRepository: NewsFeedRecommendationRepository,
  engine:                           NewsFeedRecommendationEngine
)(implicit options:                 ZActivityOptions[Any])
    extends ProcessorActivities {
  override def loadReaderWithArticles(params: LoadReaderParams): ReaderWithArticles =
    ZActivity.run {
      for {
        reader <- readerRepository
                    .findById(params.readerId.fromProto)
                    .someOrFail(ReaderNotFoundException(params.readerId.fromProto))

        topic <- newsFeedRepository
                   .findTopicById(params.topicId.fromProto)
                   .someOrFail(TopicNotFoundException(params.topicId.fromProto))

        articles <- newsFeedRepository.articlesForTopic(
                      topicId = params.topicId.fromProto,
                      now = params.forDate.fromProto[LocalDateTime]
                    )
      } yield ReaderWithArticles(
        reader = Reader(
          id = reader.id,
          registeredAt = reader.registeredAt
        ),
        topic = NewsFeedTopic(
          id = topic.id,
          owner = topic.owner,
          topic = topic.topic,
          lang = topic.lang
        ),
        articles = articles.map { article =>
          NewsFeedArticle(
            id = article.id,
            topic = article.topic,
            title = article.title,
            description = article.description,
            publishedAt = article.publishedAt
          )
        }
      )
    }
}
