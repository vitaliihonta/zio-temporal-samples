package dev.vhonta.content.processor.launcher.workflow

import dev.vhonta.content.processor.ProcessingResult
import dev.vhonta.content.processor.launcher.BuildInfo
import org.apache.spark.launcher.SparkLauncher
import zio.{BuildInfo => _, _}
import zio.stream.{BuildInfo => _, _}
import zio.json.readJsonLinesAs
import zio.temporal._
import dev.vhonta.content.processor.proto
import dev.vhonta.content.processor.proto.{
  SparkLaunchKubernetesPayload,
  SparkLaunchLocalPayload,
  SparkLauncherParams,
  SparkReadResultsParams,
  SparkReadResultsValue
}
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.time.LocalDate
import zio.nio.file.Files
import zio.nio.file.Path
import java.nio.file.NoSuchFileException

case class SparkProcessFailedException(message: String) extends Exception(message)

@activityInterface
trait ProcessorLauncherActivity {
  def launchProcessorJob(params: SparkLauncherParams): Unit

  def getResults(params: SparkReadResultsParams): SparkReadResultsValue
}

object ProcessorLauncherActivityImpl {
  case class LauncherConfig(
    sparkHome:   String,
    artifactDir: String)

  private val config = (Config.string("spark_home") ++ Config.string("artifact_dir"))
    .nested("processor", "launcher")
    .map((LauncherConfig.apply _).tupled)

  val make: ZLayer[ZActivityOptions[Any], Config.Error, ProcessorLauncherActivity] =
    ZLayer.fromZIO(ZIO.config(config)) >>> ZLayer.fromFunction(
      ProcessorLauncherActivityImpl(_: LauncherConfig)(_: ZActivityOptions[Any])
    )
}

case class ProcessorLauncherActivityImpl(
  config:           ProcessorLauncherActivityImpl.LauncherConfig
)(implicit options: ZActivityOptions[Any])
    extends ProcessorLauncherActivity {

  override def launchProcessorJob(params: SparkLauncherParams): Unit =
    ZActivity.run {
      ZIO.clockWith(_.localDateTime).flatMap { today =>
        val date = today.toLocalDate
        params.payload match {
          case local: SparkLaunchLocalPayload    => launchLocally(params, local, date)
          case k8s: SparkLaunchKubernetesPayload => launchKubernetes(params, k8s, date)
        }
      }
    }

  override def getResults(params: SparkReadResultsParams): SparkReadResultsValue = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Getting runId=${params.runId} result")
        results <- Files
                     .find(
                       Path(sys.env("PWD"), "processor-results", params.runId),
                       maxDepth = 1
                     )((path, attrs) => attrs.isRegularFile && path.filename.toString.endsWith(".json"))
                     .flatMap(path => readJsonLinesAs[ProcessingResult](path.toFile))
                     .runCollect
                     .catchSome { case _: NoSuchFileException =>
                       ZIO.succeed(Chunk.empty[ProcessingResult])
                     }
      } yield {
        SparkReadResultsValue(
          results = results.map { result =>
            proto.ProcessingResult(
              integration = result.integration,
              date = result.date,
              inserted = result.inserted
            )
          }
        )
      }
    }
  }

  private def launchLocally(
    params:  SparkLauncherParams,
    payload: SparkLaunchLocalPayload,
    date:    LocalDate
  ): Task[Unit] = {
    val fatJarPath = s"${config.artifactDir}/${BuildInfo.contentProcessorJobFile}"
    val launcher = new SparkLauncher()
      .setMainClass(BuildInfo.contentProcessorJobMainClass)
      .setAppName(BuildInfo.contentProcessorJobName)
      .setSparkHome(config.sparkHome)
      .setAppResource(fatJarPath)
      .addJar(fatJarPath)
      .addAppArgs(
        // format: off
        "--mode", "submit",
        s"--run-id", params.runId,
        s"--date", date.toString,
        s"--timeout", params.sparkJobTimeout.fromProto[Duration].toSeconds.toString + "s"
        // format: on
      )

    for {
      _       <- ZIO.logInfo("Starting Spark process on the local machine")
      process <- ZIO.attemptBlockingIO(launcher.launch())
      _       <- ZIO.logInfo(s"Started process pid=${process.pid()}. Waiting for the process to finish...")

      _ <- streamConsoleOut(process)
             .timeout(params.sparkJobTimeout.fromProto[Duration] + 5.minutes)
             .map(_.isEmpty)
             .flatMap(
               ZIO.when(_)(
                 ZIO.attemptBlockingIO(process.destroy())
               )
             )
             .fork

      _ <- ZIO.fromCompletableFuture(process.onExit())

      exitCode = process.exitValue()
      _ <- ZIO.logInfo(s"Process result=$exitCode")
      _ <- ZIO.when(exitCode != 0) {
             ZIO.fail(SparkProcessFailedException(s"Spark process failed with non-zero exit code: $exitCode"))
           }
    } yield ()
  }

  private def launchKubernetes(
    params:  SparkLauncherParams,
    payload: SparkLaunchKubernetesPayload,
    date:    LocalDate
  ): Task[Unit] =
    ZIO.fail(new NotImplementedError("Kubernetes launcher not supported yet"))

  private def streamConsoleOut(process: Process): Task[Unit] = {
    streamStdout(process.getInputStream).zipPar(streamStdout(process.getErrorStream))
  }

  private def streamStdout(inputStream: => java.io.InputStream): Task[Unit] = {
    ZStream
      .fromInputStreamZIO(
        ZIO.attemptBlockingIO(inputStream)
      )
      .via(ZPipeline.utfDecode)
      .mapZIO(line => ZIO.consoleWith(_.print(line)))
      .runDrain
  }
}
