package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.{NewsFeedArticle, NewsFeedTopic}
import io.getquill.SnakeCase
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

object NewsFeedRepository {
  val make: URLayer[PostgresQuill[SnakeCase], NewsFeedRepository] =
    ZLayer.fromFunction(NewsFeedRepository(_: PostgresQuill[SnakeCase]))
}

case class NewsFeedRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._
  import quill.extras._

  def createTopic(topic: NewsFeedTopic): IO[SQLException, NewsFeedTopic] = {
    val insert = quote {
      query[NewsFeedTopic].insertValue(lift(topic))
    }
    run(insert).as(topic)
  }

  def findTopicById(topicId: UUID): IO[SQLException, Option[NewsFeedTopic]] = {
    val select = quote {
      query[NewsFeedTopic].filter(_.id == lift(topicId)).take(1)
    }
    run(select).map(_.headOption)
  }

  def listTopics(readers: Set[UUID]): IO[SQLException, List[NewsFeedTopic]] = {
    val select = quote {
      query[NewsFeedTopic].filter(t => liftQuery(readers).contains(t.owner))
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
        .filter(_.publishedAt <= lift(now))
    }
    run(select)
  }
}
