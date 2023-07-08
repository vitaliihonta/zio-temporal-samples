package dev.vhonta.content.repository

import zio._
import dev.vhonta.content.{ContentFeedRecommendationItem, ContentFeedTopic}
import io.getquill.SnakeCase
import java.sql.SQLException
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

  def deleteTopicById(topicId: UUID): Task[Boolean] = {
    val deleteRecommendationItems = quote {
      query[ContentFeedRecommendationItem]
        .filter(_.topic == lift(Option(topicId)))
        .delete
    }
    val deleteSelf = quote {
      query[ContentFeedTopic]
        .filter(_.id == lift(topicId))
        .delete
    }
    transaction {
      run(deleteRecommendationItems) *>
        run(deleteSelf)
    }.map(_ > 0)
  }

  def listTopics(subscribers: Option[Set[UUID]]): IO[SQLException, List[ContentFeedTopic]] = {
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
}
