package dev.vhonta.content.processor.job.recommendations

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.{ContentFeedItemRow, ContentFeedRecommendationItemRow}
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.IntegerType
import java.time.LocalDate

class RecommendationsEngine()(implicit spark: SparkSession) extends LazyLogging with Serializable {
  import spark.implicits._

  def createRecommendations(
    contentDS: Dataset[ContentFeedItemRow],
    forDate:   LocalDate
  ): Dataset[ContentFeedRecommendationItemRow] = {
    val recommendationsDS = contentDS
      .where($"pulledDate" === lit(forDate))
      .distinct()
      .groupBy($"integration")
      .agg(
        // Pretty dumb algorithm ¯\_(ツ)_/¯
        slice(
          array_sort(
            collect_list(
              struct(
                $"topic",
                $"title",
                coalesce($"description", lit("")).as("description"),
                $"url",
                lit(forDate).as("forDate"),
                $"contentType",
                $"publishedAt"
              )
            ),
            (x, y) =>
              (unix_timestamp(x("publishedAt")) - unix_timestamp(y("publishedAt")))
                .cast(IntegerType)
          ),
          start = 1,
          length = 5
        ).as("recommendations")
      )
      .withColumn("recommendation", explode($"recommendations"))
      .select(
        $"integration",
        $"recommendation.topic",
        $"recommendation.title",
        $"recommendation.description",
        $"recommendation.url",
        $"recommendation.forDate",
        $"recommendation.contentType"
      )
      .as[ContentFeedRecommendationItemRow]

    logger.info(s"Created ${recommendationsDS.count()} recommendations!")

    recommendationsDS
  }
}
