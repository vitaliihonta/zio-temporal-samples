package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.{NewsFeedArticle, NewsFeedTopic}
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

object NewsFeedRepository {
  val make: URLayer[Quill.Postgres[SnakeCase], NewsFeedRepository] =
    ZLayer.fromFunction(NewsFeedRepository(_: Quill.Postgres[SnakeCase]))
}

case class NewsFeedRepository(quill: Quill.Postgres[SnakeCase]) {
  import quill._
  import quill.extras._

  def createTopic(topic: NewsFeedTopic): IO[SQLException, NewsFeedTopic] = {
    val insert = quote {
      query[NewsFeedTopic].insertValue(lift(topic))
    }
    run(insert).as(topic)
  }

  def listAllTopics: IO[SQLException, List[NewsFeedTopic]] = {
    val select = quote {
      query[NewsFeedTopic]
    }
    run(select)
  }

  def storeArticles(articles: List[NewsFeedArticle]): IO[SQLException, Unit] = {
    val insert = quote {
      liftQuery(articles).foreach(e => query[NewsFeedArticle].insertValue(e))
    }
    run(insert).unit
  }

  def articlesForTopic(topicId: UUID, now: LocalDateTime): IO[SQLException, List[NewsFeedArticle]] = {
    val select = quote {
      query[NewsFeedArticle]
        .filter(_.topic == lift(topicId))
        .filter(_.publishedAt < lift(now))
    }
    run(select)
  }
}
