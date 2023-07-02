package dev.vhonta.content.processor.job

import org.apache.spark.sql._
import org.slf4j.LoggerFactory
import scala.util.Using

object Main {
  @transient private lazy val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("Starting spark session...")
    Using.resource(buildSparkSession()) { spark =>
      import spark.implicits._

      spark.read
        .parquet(datalakePath())
        .show(truncate = false)
    }
    logger.info("Job finished successfully!")
  }

  private def buildSparkSession(): SparkSession = {
    sys.env.get("JOB_MODE") match {
      case Some("submit") =>
        SparkSession.builder().getOrCreate()
      case _ =>
        SparkSession
          .builder()
          .master("local[*]")
          .appName("local-job")
          .getOrCreate()
    }
  }

  private def datalakePath(): String =
    sys.env.getOrElse("DATALAKE_PATH", sys.env("PWD") + "/datalake")
}
