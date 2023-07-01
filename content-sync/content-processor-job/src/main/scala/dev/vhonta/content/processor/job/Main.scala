package dev.vhonta.content.processor.job

import org.apache.spark.sql._
import org.slf4j.LoggerFactory
import scala.util.Using

object Main {
  @transient private lazy val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting spark session...")
    Using.resource(SparkSession.builder().getOrCreate()) { spark =>
      import spark.implicits._

      val data = List(1, 2, 3).toDF("id")
      data.show(truncate = false)
    }
    logger.info("Job finished successfully!")
  }
}
