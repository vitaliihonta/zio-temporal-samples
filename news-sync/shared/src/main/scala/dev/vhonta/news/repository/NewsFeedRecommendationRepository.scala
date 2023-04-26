package dev.vhonta.news.repository

import dev.vhonta.news.{NewsFeedArticle, NewsFeedRecommendation, NewsFeedRecommendationArticle}
import io.getquill.SnakeCase
import zio._
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

// TODO: finish
class NewsFeedRecommendationRepository(quill: PostgresQuill[SnakeCase]) {

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

  def getForDate(topicId: UUID, date: LocalDate): IO[SQLException, NewsFeedRecommendation.View] = {
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
    run(articlesQuery).map { articles =>
      NewsFeedRecommendation.View(
        topic = topicId,
        date = date,
        articles = articles
      )
    }
  }
}
