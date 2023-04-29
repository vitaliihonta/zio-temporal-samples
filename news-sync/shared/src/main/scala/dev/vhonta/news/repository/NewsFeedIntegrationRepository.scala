package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.{NewsFeedIntegration, NewsFeedIntegrationType}
import io.getquill.{Query, SnakeCase}

import java.sql.SQLException
import java.util.UUID

object NewsFeedIntegrationRepository {
  val make: URLayer[PostgresQuill[SnakeCase], NewsFeedIntegrationRepository] =
    ZLayer.fromFunction(NewsFeedIntegrationRepository(_: PostgresQuill[SnakeCase]))
}

case class NewsFeedIntegrationRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._

  def create(integration: NewsFeedIntegration): IO[SQLException, NewsFeedIntegration] = {
    val insert = quote {
      query[NewsFeedIntegration].insertValue(lift(integration))
    }
    run(insert).as(integration)
  }

  def findAllOwnedBy(reader: UUID): IO[SQLException, List[NewsFeedIntegration]] = {
    val select = quote {
      query[NewsFeedIntegration].filter(_.reader == lift(reader))
    }
    run(select)
  }

  def findByType(integrationType: NewsFeedIntegrationType): IO[SQLException, List[NewsFeedIntegration]] = {
    val select = quote { integrationType: String =>
      sql"""SELECT * FROM news_feed_integration
            WHERE integration->>'type' = $integrationType """
        .as[Query[NewsFeedIntegration]]
    }

    run(select(lift(integrationType.entryName)))
  }
}
