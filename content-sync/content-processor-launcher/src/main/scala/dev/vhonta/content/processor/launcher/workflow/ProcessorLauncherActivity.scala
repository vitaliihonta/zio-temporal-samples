package dev.vhonta.content.processor.launcher.workflow

import dev.vhonta.content.processor.launcher.BuildInfo
import org.apache.spark.launcher.SparkLauncher
import zio.{BuildInfo => _, _}
import zio.stream.{BuildInfo => _, _}
import zio.temporal._
import dev.vhonta.content.processor.proto
import dev.vhonta.content.processor.proto.{SparkLaunchKubernetesParams, SparkLaunchLocalParams, SparkLauncherParams}
import zio.temporal.activity._
import zio.temporal.protobuf.syntax.FromProtoTypeSyntax

import java.time.LocalDate

case class SparkProcessFailedException(message: String) extends Exception(message)

@activityInterface
trait ProcessorLauncherActivity {
  def launchProcessorJob(params: proto.SparkLauncherParams): Unit
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
        params match {
          case local: SparkLaunchLocalParams    => launchLocally(local, today.toLocalDate)
          case k8s: SparkLaunchKubernetesParams => launchKubernetes(k8s)
        }
      }
    }

  private def launchLocally(params: SparkLaunchLocalParams, date: LocalDate): Task[Unit] = {
    val launcher = new SparkLauncher()
      .setMainClass(BuildInfo.contentProcessorJobMainClass)
      .setAppName(BuildInfo.contentProcessorJobName)
      .setSparkHome(config.sparkHome)
      .setAppResource(
        s"${config.artifactDir}/${BuildInfo.contentProcessorJobFile}"
      )
      .addAppArgs(
        // format: off
        "--mode", "submit",
        s"--date", date.toString,
        s"--timeout", (params.jobTimeout.fromProto[Duration] minus 5.minutes).toSeconds.toString + "s"
        // format: on
      )

    for {
      _       <- ZIO.logInfo("Starting Spark process on the local machine")
      process <- ZIO.attemptBlockingIO(launcher.launch())
      _       <- ZIO.logInfo(s"Started process pid=${process.pid()}. Waiting for the process to finish...")

      _ <- streamConsoleOut(process)
             .timeout(params.jobTimeout.fromProto[Duration])
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

  private def launchKubernetes(params: SparkLaunchKubernetesParams): Task[Unit] =
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
