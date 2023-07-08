package dev.vhonta.content.processor.job.processor

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.{ContentFeedItemRow, ContentFeedRecommendationItemRow, ProcessingResult}
import dev.vhonta.content.processor.job.recommendations.RecommendationsEngine
import org.apache.spark.sql.{Dataset, SparkSession}

import java.time.LocalDate
import scala.util.Using

class ContentProcessor(engine: RecommendationsEngine)(implicit spark: SparkSession)
    extends LazyLogging
    with Serializable {

  import spark.implicits._

  def process(
    contentDS: Dataset[ContentFeedItemRow],
    date:      LocalDate
  ): Dataset[ProcessingResult] = {
    engine
      .createRecommendations(contentDS, date)
      .repartition(numPartitions = 8, $"integration")
      .groupByKey(_.integration)
      .mapGroups { (integration: Long, batch: Iterator[ContentFeedRecommendationItemRow]) =>
        Using.resource(ContentFeedRecommendationItemRepository(configPath = "db")) { repo =>
          val inserted = batch
            .grouped(100)
            .map { items =>
              repo.insert(
                integration,
                date,
                items.toList
              )
            }
            .sum

          logger.info(s"Total inserted: $inserted")

          ProcessingResult(
            integration = integration,
            date = date,
            inserted = inserted
          )
        }
      }
  }
}
