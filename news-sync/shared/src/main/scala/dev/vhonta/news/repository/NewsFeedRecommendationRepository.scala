package dev.vhonta.news.repository

import dev.vhonta.news.{NewsFeedTopic, NewsFeedArticle, NewsFeedRecommendation, NewsFeedRecommendationArticle}
import io.getquill.SnakeCase
import zio._
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

object NewsFeedRecommendationRepository {
  val make: URLayer[PostgresQuill[SnakeCase], NewsFeedRecommendationRepository] =
    ZLayer.fromFunction(NewsFeedRecommendationRepository(_))
}

case class NewsFeedRecommendationRepository(quill: PostgresQuill[SnakeCase]) {

  import quill._

  def create(
    recommendation: NewsFeedRecommendation,
    articles:       List[NewsFeedRecommendationArticle]
  ): Task[Unit] = {
    val insert = quote {
      query[NewsFeedRecommendation].insertValue(lift(recommendation))
    }
    val insertArticles = quote {
      liftQuery(articles).foreach(e => query[NewsFeedRecommendationArticle].insertValue(e))
    }

    transaction(
      run(insert) *> run(insertArticles)
    ).unit
  }

  def existForDate(topicId: UUID, date: LocalDate): IO[SQLException, Boolean] = {
    val check = quote {
      query[NewsFeedRecommendation]
        .filter(_.topic == lift(topicId))
        .filter(_.forDate == lift(date))
        .nonEmpty
    }
    run(check)
  }

  def getForDate(topicId: UUID, date: LocalDate): IO[SQLException, Option[NewsFeedRecommendation.View]] = {
    val recommendationIdQuery = quote {
      query[NewsFeedRecommendation]
        .filter(_.topic == lift(topicId))
        .filter(_.forDate == lift(date))
        .map(_.id)
    }
    val articlesQuery = quote {
      query[NewsFeedRecommendationArticle]
        .filter(art => recommendationIdQuery.contains(art.recommendation))
        .join(query[NewsFeedArticle])
        .on(_.article == _.id)
        .map { case (_, art) => art }
    }
    val topicNameQuery = quote {
      query[NewsFeedTopic]
        .filter(_.id == lift(topicId))
        .map(_.topic)
        .take(1)
    }

    run(topicNameQuery)
      .map(_.headOption)
      .flatMap(
        ZIO.foreach(_) { topicName =>
          run(articlesQuery).map { articles =>
            NewsFeedRecommendation.View(
              topicId = topicId,
              topic = topicName,
              date = date,
              articles = articles
            )
          }
        }
      )
  }
}
