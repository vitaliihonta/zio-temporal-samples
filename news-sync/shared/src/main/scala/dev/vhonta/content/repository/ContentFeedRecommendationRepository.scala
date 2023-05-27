package dev.vhonta.content.repository

import dev.vhonta.content.{ContentFeedTopic, ContentFeedItem, ContentFeedRecommendation, ContentFeedRecommendationItem}
import io.getquill.SnakeCase
import zio._
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

object ContentFeedRecommendationRepository {
  val make: URLayer[PostgresQuill[SnakeCase], ContentFeedRecommendationRepository] =
    ZLayer.fromFunction(ContentFeedRecommendationRepository(_))
}

case class ContentFeedRecommendationRepository(quill: PostgresQuill[SnakeCase]) {

  import quill._

  def create(
    recommendation: ContentFeedRecommendation,
    items:          List[ContentFeedRecommendationItem]
  ): Task[Unit] = {
    val insert = quote {
      query[ContentFeedRecommendation].insertValue(lift(recommendation))
    }
    val insertItems = quote {
      liftQuery(items).foreach(e => query[ContentFeedRecommendationItem].insertValue(e))
    }

    transaction(
      run(insert) *> run(insertItems)
    ).unit
  }

  def existForDate(topicId: UUID, date: LocalDate): IO[SQLException, Boolean] = {
    val check = quote {
      query[ContentFeedRecommendation]
        .filter(_.topic == lift(topicId))
        .filter(_.forDate == lift(date))
        .nonEmpty
    }
    run(check)
  }

  def getForDate(topicId: UUID, date: LocalDate): IO[SQLException, Option[ContentFeedRecommendation.View]] = {
    val recommendationIdQuery = quote {
      query[ContentFeedRecommendation]
        .filter(_.topic == lift(topicId))
        .filter(_.forDate == lift(date))
        .map(_.id)
    }
    val itemsQuery = quote {
      query[ContentFeedRecommendationItem]
        .filter(item => recommendationIdQuery.contains(item.recommendation))
        .join(query[ContentFeedItem])
        .on(_.item == _.id)
        .map { case (_, art) => art }
    }
    val topicNameQuery = quote {
      query[ContentFeedTopic]
        .filter(_.id == lift(topicId))
        .map(_.topic)
        .take(1)
    }

    run(topicNameQuery)
      .map(_.headOption)
      .flatMap(
        ZIO.foreach(_) { topicName =>
          run(itemsQuery).map { items =>
            ContentFeedRecommendation.View(
              topicId = topicId,
              topic = topicName,
              date = date,
              items = items
            )
          }
        }
      )
  }
}
