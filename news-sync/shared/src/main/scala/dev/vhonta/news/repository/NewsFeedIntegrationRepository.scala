package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.{NewsFeedIntegration, NewsFeedIntegrationType}
import io.getquill.{Query, SnakeCase}
import java.sql.SQLException

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

  def findByType(integrationType: NewsFeedIntegrationType): IO[SQLException, List[NewsFeedIntegration]] = {
    val select = quote { integrationType: NewsFeedIntegrationType =>
      sql"""SELECT x.reader, x.integration FROM news_feed_integration x
            WHERE x->>'type' = '$integrationType' """
        .as[Query[NewsFeedIntegration]]
    }

    run(select(lift(integrationType)))
  }
}
