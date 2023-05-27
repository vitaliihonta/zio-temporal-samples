package dev.vhonta.content.repository

import zio._
import dev.vhonta.content.{ContentFeedItem, ContentFeedTopic}
import io.getquill.SnakeCase
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

object ContentFeedRepository {
  val make: URLayer[PostgresQuill[SnakeCase], ContentFeedRepository] =
    ZLayer.fromFunction(ContentFeedRepository(_: PostgresQuill[SnakeCase]))
}

case class ContentFeedRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._
  import quill.extras._

  def createTopic(topic: ContentFeedTopic): IO[SQLException, ContentFeedTopic] = {
    val insert = quote {
      query[ContentFeedTopic].insertValue(lift(topic))
    }
    run(insert).as(topic)
  }

  def findTopicById(topicId: UUID): IO[SQLException, Option[ContentFeedTopic]] = {
    val select = quote {
      query[ContentFeedTopic].filter(_.id == lift(topicId)).take(1)
    }
    run(select).map(_.headOption)
  }

  def listTopics(subscribers: Option[Set[UUID]] = None): IO[SQLException, List[ContentFeedTopic]] = {
    subscribers match {
      case None =>
        val select = quote(query[ContentFeedTopic])
        run(select)
      case Some(subscribers) =>
        val select = quote {
          query[ContentFeedTopic].filter(t => liftQuery(subscribers).contains(t.owner))
        }
        run(select)
    }
  }

  def storeItems(items: List[ContentFeedItem]): IO[SQLException, Unit] = {
    val insert = quote {
      liftQuery(items).foreach(e => query[ContentFeedItem].insertValue(e))
    }
    run(insert).unit
  }

  def itemsForTopic(topicId: UUID, now: LocalDateTime): IO[SQLException, List[ContentFeedItem]] = {
    val select = quote {
      query[ContentFeedItem]
        .filter(_.topic == lift(Option(topicId)))
        .filter(_.publishedAt <= lift(now))
    }
    run(select)
  }
}
