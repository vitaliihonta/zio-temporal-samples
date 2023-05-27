package dev.vhonta.content.repository

import zio._
import dev.vhonta.content.{ContentFeedIntegration, ContentFeedIntegrationDetails, ContentFeedIntegrationType}
import io.getquill.{Query, SnakeCase}

import java.sql.SQLException
import java.util.UUID

object ContentFeedIntegrationRepository {
  val make: URLayer[PostgresQuill[SnakeCase], ContentFeedIntegrationRepository] =
    ZLayer.fromFunction(ContentFeedIntegrationRepository(_: PostgresQuill[SnakeCase]))
}

case class ContentFeedIntegrationRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._

  def create(integration: ContentFeedIntegration): IO[SQLException, ContentFeedIntegration] = {
    val insert = quote {
      query[ContentFeedIntegration]
        .insertValue(lift(integration))
        .returningGenerated(_.id)
    }
    run(insert).as(integration)
  }

  def findById(integrationId: Long): IO[SQLException, Option[ContentFeedIntegration]] = {
    val select = quote {
      query[ContentFeedIntegration]
        .filter(_.id == lift(integrationId))
        .take(1)
    }

    run(select).map(_.headOption)
  }

  def deleteById(integrationId: Long): IO[SQLException, Boolean] = {
    val delete = quote(
      query[ContentFeedIntegration]
        .filter(_.id == lift(integrationId))
        .delete
    )

    run(delete).map(_ > 0)
  }

  def updateDetails(
    integrationId: Long,
    newDetails:    ContentFeedIntegrationDetails
  ): IO[SQLException, ContentFeedIntegration] = {
    val update = quote {
      query[ContentFeedIntegration]
        .filter(_.id == lift(integrationId))
        .update(_.integration -> lift(newDetails))
        .returning(x => x)
    }

    run(update)
  }

  def findAllOwnedBy(subscriber: UUID): IO[SQLException, List[ContentFeedIntegration]] = {
    val select = quote {
      query[ContentFeedIntegration].filter(_.subscriber == lift(subscriber))
    }
    run(select)
  }

  def findByType(integrationType: ContentFeedIntegrationType): IO[SQLException, List[ContentFeedIntegration]] = {
    val select = quote { integrationType: String =>
      sql"""SELECT * FROM content_feed_integration
            WHERE integration->>'type' = $integrationType """
        .as[Query[ContentFeedIntegration]]
    }

    run(select(lift(integrationType.entryName)))
  }
}
