package dev.vhonta.content.processor.job

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.job.processor.ContentProcessor
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{Dataset, Encoder, SparkSession}

class JobRunner(
  processor:      ContentProcessor
)(implicit spark: SparkSession)
    extends LazyLogging
    with Serializable {

  import spark.implicits._

  def regularProcess(params: JobParameters): Unit = {
    val streamingQuery = spark.readStream
      .schema(schemaOf[ContentFeedItemRow])
      .parquet(params.inputPath)
      .as[ContentFeedItemRow]
      .writeStream
      .option("checkpointLocation", params.checkpointLocation)
      .trigger(Trigger.AvailableNow())
      .foreachBatch { (contentDS: Dataset[ContentFeedItemRow], batchId: Long) =>
        logger.info(s"Processing batch=$batchId numRecords=${contentDS.count()}")
        processor
          .process(contentDS, params.date)
          .write
          .partitionBy("date")
          .json(params.resultPath)

        logger.info("Results stored!")
      }
      .start()

    val finished = streamingQuery.awaitTermination(params.timeout.toMillis)
    if (!finished) {
      logger.warn(s"Took more than timeout=${params.timeout} to process the query, terminating")
      streamingQuery.stop()
    }
  }

  private def schemaOf[T: Encoder]: StructType =
    implicitly[Encoder[T]].schema
}
