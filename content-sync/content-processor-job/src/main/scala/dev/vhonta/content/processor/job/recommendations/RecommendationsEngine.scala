package dev.vhonta.content.processor.job.recommendations

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.{ContentFeedItemRow, ContentFeedRecommendationItemRow}
import org.apache.spark.sql.{Dataset, SparkSession}
import java.time.LocalDate

class RecommendationsEngine()(implicit spark: SparkSession) extends LazyLogging with Serializable {
  import spark.implicits._

  def createRecommendations(
    contentDS: Dataset[ContentFeedItemRow],
    forDate:   LocalDate
  ): Dataset[ContentFeedRecommendationItemRow] = {
    // TODO: implement
    logger.info(s"Content for date=$forDate")
    contentDS.show(truncate = false)
    spark.emptyDataset[ContentFeedRecommendationItemRow]
  }
}
