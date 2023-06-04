package dev.vhonta.content.puller.workflows.youtube

import zio._
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.testkit.{ZTestActivityEnvironment, ZTestEnvironmentOptions, ZTestWorkflowEnvironment}
import zio.temporal.worker.ZWorkerFactoryOptions
import zio.temporal.workflow.ZWorkflowClientOptions

object TestModule {
  val workflowTestEnv: ZLayer[
    Any,
    Config.Error,
    ZWorkflowClientOptions with ZWorkerFactoryOptions with ZTestEnvironmentOptions with ZTestWorkflowEnvironment[Any]
  ] =
    ZLayer.make[
      ZWorkflowClientOptions with ZWorkerFactoryOptions with ZTestEnvironmentOptions with ZTestWorkflowEnvironment[Any]
    ](
      ZTestWorkflowEnvironment.make[Any],
      ZTestEnvironmentOptions.make,
      ZWorkerFactoryOptions.make,
      ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withDataConverter(ProtobufDataConverter.makeAutoLoad())
    )

  val activityTestEnv: ZLayer[
    Any,
    Config.Error,
    ZWorkflowClientOptions with ZWorkerFactoryOptions with ZTestEnvironmentOptions with ZTestActivityEnvironment[Any]
  ] =
    ZLayer.make[
      ZWorkflowClientOptions with ZWorkerFactoryOptions with ZTestEnvironmentOptions with ZTestActivityEnvironment[Any]
    ](
      ZTestActivityEnvironment.make[Any],
      ZTestEnvironmentOptions.make,
      ZWorkerFactoryOptions.make,
      ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withDataConverter(ProtobufDataConverter.makeAutoLoad())
    )
}
