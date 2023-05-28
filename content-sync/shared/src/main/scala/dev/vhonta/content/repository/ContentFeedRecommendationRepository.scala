package dev.vhonta.content.repository

import dev.vhonta.content.{
  ContentFeedIntegration,
  ContentFeedItem,
  ContentFeedRecommendation,
  ContentFeedRecommendationItem
}
import io.getquill.SnakeCase
import zio._
import java.sql.SQLException
import java.time.LocalDate

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

  def existForDate(integrationId: Long, date: LocalDate): IO[SQLException, Boolean] = {
    val check = quote {
      query[ContentFeedRecommendation]
        .filter(_.integration == lift(integrationId))
        .filter(_.forDate == lift(date))
        .nonEmpty
    }
    run(check)
  }

  def getForDate(integrationId: Long, date: LocalDate): IO[SQLException, Option[ContentFeedRecommendation.View]] = {
    val recommendationIdQuery = quote {
      query[ContentFeedRecommendation]
        .filter(_.integration == lift(integrationId))
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
    val integrationQuery = quote {
      query[ContentFeedIntegration]
        .filter(_.id == lift(integrationId))
        .take(1)
    }

    run(integrationQuery)
      .map(_.headOption)
      .flatMap(
        ZIO.foreach(_) { integration =>
          run(itemsQuery).map { items =>
            ContentFeedRecommendation.View(
              integration = integration,
              date = date,
              items = items
            )
          }
        }
      )
  }
}
