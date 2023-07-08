package dev.vhonta.content.processor.job.processor

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.HikariDataSource
import dev.vhonta.content.processor.ContentFeedRecommendationItemRow
import io.getquill.{PostgresJdbcContext, SnakeCase}
import dev.vhonta.content.{
  ContentFeedIntegration,
  ContentFeedRecommendation,
  ContentFeedRecommendationItem,
  ContentType,
  Subscriber
}
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.{PSQLException, PSQLState}

import java.time.LocalDate
import java.util.UUID

object ContentFeedRecommendationItemRepository extends LazyLogging {

  def apply(configPath: String): ContentFeedRecommendationItemRepository = {
    new ContentFeedRecommendationItemRepository(
      new PostgresJdbcContext[SnakeCase](SnakeCase, JdbcDataSources.load(configPath))
    )
  }
}

class ContentFeedRecommendationItemRepository(@transient private val ctx: PostgresJdbcContext[SnakeCase])
    extends LazyLogging
    with Serializable
    with AutoCloseable {

  import ctx._

  override def close(): Unit = ctx.close()

  // TODO: handle race
  def insert(
    integration: Long,
    forDate:     LocalDate,
    items:       Seq[ContentFeedRecommendationItemRow]
  ): Long = {
    withOwner(integration) { owner =>
      val recommendationId = getOrCreateRecommendationId(integration, forDate, owner)

      val recommendationItems = items.map { item =>
        ContentFeedRecommendationItem(
          recommendation = recommendationId,
          topic = item.topic.map(UUID.fromString),
          title = item.title,
          description = item.description,
          url = item.url,
          contentType = ContentType.withName(item.contentType)
        )
      }
      val insertItems = quote {
        liftQuery(recommendationItems).foreach(r => query[ContentFeedRecommendationItem].insertValue(r))
      }

      logger.info(
        s"Integration=$integration creating recommendation=$recommendationId with ${recommendationItems.size} items"
      )
      // insert items & return
      run(insertItems).sum
    }
  }

  private def withOwner(integration: Long)(thunk: Subscriber => Long): Long = {
    val selectOwner = quote {
      query[Subscriber]
        .filter(subs =>
          query[ContentFeedIntegration]
            .filter(_.id == lift(integration))
            .map(_.subscriber)
            .contains(subs.id)
        )
        .take(1)
    }

    run(selectOwner).headOption match {
      case None =>
        logger.warn(s"Owner for integration=$integration not found!")
        0

      case Some(owner) =>
        thunk(owner)
    }
  }

  private def getOrCreateRecommendationId(integration: Long, forDate: LocalDate, owner: Subscriber): UUID = {
    def findRecommendation(): Option[ContentFeedRecommendation] = {
      val findRecommendationQuery = quote {
        query[ContentFeedRecommendation]
          .filter(_.integration == lift(integration))
          .filter(_.forDate == lift(forDate))
          .filter(_.owner == lift(owner.id))
          .take(1)
      }

      run(findRecommendationQuery).headOption
    }

    findRecommendation() match {
      case Some(recommendation) =>
        recommendation.id

      case None =>
        val id = UUID.randomUUID()
        val insertRecommendation = quote {
          query[ContentFeedRecommendation].insertValue(
            lift(
              ContentFeedRecommendation(
                id = id,
                owner = owner.id,
                integration = integration,
                forDate = forDate
              )
            )
          )
        }

        try {
          run(insertRecommendation)
          id
        } catch {
          // in case of race conditions
          case e: PSQLException =>
            findRecommendation()
              .map(_.id)
              .getOrElse(
                throw new RuntimeException(
                  s"Unexpected race condition: cannot create nor find a recommendation for integration=$id date=$forDate",
                  e
                )
              )
        }
    }
  }
}
