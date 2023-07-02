package dev.vhonta.content.processor.job

import com.typesafe.scalalogging.LazyLogging
import dev.vhonta.content.processor.job.processor.ContentProcessor
import dev.vhonta.content.processor.job.recommendations.RecommendationsEngine
import org.apache.spark.sql._
import scala.util.Using

object Main extends LazyLogging with Serializable {

  def main(args: Array[String]): Unit = {
    logger.info("Starting spark session...")
    val jobParameters = JobParameters
      .parse(args, JobParameters())
      .getOrElse {
        throw new RuntimeException(s"Job parameters didn't parse args=${args.mkString(", ")}")
      }

    logger.info(s"Running the job with parameters=$jobParameters")

    Using.resource(buildSparkSession(jobParameters.mode)) { implicit spark =>
      val recommendationsEngine = new RecommendationsEngine()
      val contentProcessor      = new ContentProcessor(recommendationsEngine)
      val jobRunner             = new JobRunner(contentProcessor)

      jobRunner.regularProcess(jobParameters)
    }
    logger.info("Job finished successfully!")
  }

  private def buildSparkSession(mod: JobMode): SparkSession = {
    mod match {
      case JobMode.Submit =>
        SparkSession.builder().getOrCreate()

      case JobMode.Local =>
        SparkSession
          .builder()
          .master("local[*]")
          .appName("local-job")
          .getOrCreate()
    }
  }
}
