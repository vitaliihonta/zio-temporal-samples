package dev.vhonta.news.processor.workflow

import dev.vhonta.news.{NewsFeedRecommendation, NewsFeedRecommendationArticle}
import zio.temporal._
import zio.temporal.activity._
import dev.vhonta.news.repository.{NewsFeedRecommendationRepository, NewsFeedRepository, ReaderRepository}
import dev.vhonta.news.processor.proto.{
  AllReadersWithTopics,
  LoadReaderParams,
  ReaderWithArticles,
  ReaderWithTopic,
  SaveRecommendationsParams,
  CheckRecommendationsExistParams,
  CheckRecommendationsExistResult
}
import dev.vhonta.news.proto.{NewsFeedArticle, NewsFeedTopic, Reader}
import zio._
import zio.temporal.protobuf.syntax._

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID

case class ReaderNotFoundException(readerId: UUID) extends Exception(s"Reader with id=$readerId not found")
case class TopicNotFoundException(topicId: UUID)   extends Exception(s"Topic with id=$topicId not found")

@activityInterface
trait ProcessorActivities {
  @activityMethod(name = "LoadReadersArticles")
  def loadReaderWithArticles(params: LoadReaderParams): ReaderWithArticles

  @throws[ReaderNotFoundException]
  @throws[TopicNotFoundException]
  def loadAllReadersWithTopics(): AllReadersWithTopics

  def createRecommendations(params: SaveRecommendationsParams): Unit

  def checkRecommendationsExist(params: CheckRecommendationsExistParams): CheckRecommendationsExistResult
}

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

  override def loadAllReadersWithTopics(): AllReadersWithTopics =
    ZActivity.run {
      for {
        _      <- ZIO.logInfo("Loading all readers with topics...")
        topics <- newsFeedRepository.listTopics(readers = None)
      } yield AllReadersWithTopics(
        readersWithTopics = topics.map(topic => ReaderWithTopic(readerId = topic.owner, topicId = topic.id))
      )
    }

  override def loadReaderWithArticles(params: LoadReaderParams): ReaderWithArticles =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Loading readers=${params.readerId.fromProto}")
        reader <- readerRepository
                    .findById(params.readerId.fromProto)
                    .someOrFail(ReaderNotFoundException(params.readerId.fromProto))

        _ <- ZIO.logInfo(s"Loading topic=${params.topicId.fromProto}")
        topic <- newsFeedRepository
                   .findTopicById(params.topicId.fromProto)
                   .someOrFail(TopicNotFoundException(params.topicId.fromProto))

        _ <- ZIO.logInfo(s"Loading articles topic=${params.topicId.fromProto} now=${params.forDate}")
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
            url = article.url,
            publishedAt = article.publishedAt
          )
        }
      )
    }

  override def createRecommendations(params: SaveRecommendationsParams): Unit =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(
               s"Creating recommendations reader=${params.readerWithTopic.readerId.fromProto} " +
                 s"topic=${params.readerWithTopic.readerId} " +
                 s"num_articles=${params.articleIds.size}"
             )
        recommendationId <- ZIO.randomWith(_.nextUUID)
        _ <- newsFeedRecommendationRepository.create(
               recommendation = NewsFeedRecommendation(
                 id = recommendationId,
                 owner = params.readerWithTopic.readerId.fromProto,
                 topic = params.readerWithTopic.topicId.fromProto,
                 forDate = params.forDate.fromProto[LocalDateTime].toLocalDate
               ),
               articles = params.articleIds.view.map { articleId =>
                 NewsFeedRecommendationArticle(
                   recommendation = recommendationId,
                   article = articleId.fromProto
                 )
               }.toList
             )
      } yield ()
    }

  override def checkRecommendationsExist(params: CheckRecommendationsExistParams): CheckRecommendationsExistResult =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(
               s"Checking if recommendations exist reader=${params.readerWithTopic.readerId.fromProto} " +
                 s"topic=${params.readerWithTopic.readerId}"
             )
        exist <- newsFeedRecommendationRepository.existForDate(
                   params.readerWithTopic.topicId.fromProto,
                   params.forDate.fromProto[LocalDateTime].toLocalDate
                 )
      } yield CheckRecommendationsExistResult(exist)
    }
}
