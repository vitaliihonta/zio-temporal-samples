package dev.vhonta.content.processor.job.processor

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.job.{ContentFeedItemRow, ContentFeedRecommendationItemRow, ProcessingResult}
import dev.vhonta.content.processor.job.recommendations.RecommendationsEngine
import org.apache.spark.sql.{Dataset, SparkSession}

import java.time.LocalDate

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
        val inserted = batch
          .grouped(100)
          .map { items =>
            ContentFeedRecommendationItemRepository.holder.insert(
              integration,
              date,
              items
            )
          }
          .sum

        ProcessingResult(
          integration = integration,
          date = date,
          inserted = inserted
        )
      }
  }
}
