package dev.vhonta.content.processor.job.processor

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.ContentFeedRecommendationItemRow
import io.getquill.{PostgresJdbcContext, SnakeCase}
import dev.vhonta.content.{
  ContentFeedIntegration,
  ContentFeedRecommendation,
  ContentFeedRecommendationItem,
  ContentType,
  Subscriber
}
import enumeratum.{Enum, EnumEntry}
import java.time.LocalDate
import java.util.UUID

object ContentFeedRecommendationItemRepository {

  final object holder
      extends ContentFeedRecommendationItemRepository(
        new PostgresJdbcContext[SnakeCase](SnakeCase, "db")
      ) {}
}

class ContentFeedRecommendationItemRepository(@transient private val ctx: PostgresJdbcContext[SnakeCase])
    extends LazyLogging
    with Serializable {

  import ctx._

  def insert(
    integration: Long,
    forDate:     LocalDate,
    items:       Seq[ContentFeedRecommendationItemRow]
  ): Long = {
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
        val recommendationId = UUID.randomUUID()
        val insertRecommendation = quote {
          query[ContentFeedRecommendation].insertValue(
            lift(
              ContentFeedRecommendation(
                id = recommendationId,
                owner = owner.id,
                integration = integration,
                forDate = forDate
              )
            )
          )
        }
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

        transaction {
          // create recommendation
          run(insertRecommendation)
          // insert items & return
          run(insertItems).sum
        }
    }
  }
}
