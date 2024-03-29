package dev.vhonta.content.processor.launcher

import dev.vhonta.content.processor.launcher.workflow._
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.temporal.activity.ZActivityRunOptions
import zio.temporal.schedules.{ZScheduleClient, ZScheduleClientOptions}

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    // todo: use layers
    val registerWorkflow =
      ZWorkerFactory.newWorker(ProcessorLauncherStarter.TaskQueue) @@
        ZWorker.addWorkflow[ProcessorLauncherWorkflowImpl].fromClass @@
        ZWorker.addActivityImplementationLayer(ProcessorLauncherActivityImpl.make) @@
        ZWorker.addActivityImplementationLayer(ProcessorConfigurationActivitiesImpl.make)

    val program = for {
      _    <- registerWorkflow
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      _    <- ZIO.serviceWithZIO[ProcessorLauncherStarter](_.start(args.contains("reset")))
      _    <- ZWorkerFactory.serve
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        ProcessorLauncherStarter.make,
        // temporal
        ZWorkflowClient.make,
        ZScheduleClient.make,
        ZActivityRunOptions.default,
        ZWorkflowServiceStubs.make,
        ZWorkerFactory.make,
        // options
        ZWorkflowServiceStubsOptions.make,
        ZWorkflowClientOptions.make @@
          ZWorkflowClientOptions.withDataConverter(ProtobufDataConverter.make()),
        ZWorkerFactoryOptions.make,
        ZScheduleClientOptions.make
      )
      .withConfigProvider(
        ConfigProvider.defaultProvider orElse
          TypesafeConfigProvider.fromResourcePath()
      )

  }
}
