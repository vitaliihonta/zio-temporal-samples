package dev.vhonta.content.processor

import dev.vhonta.content.processor.workflow._
import dev.vhonta.content.repository._
import io.getquill.jdbczio.Quill
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.temporal.activity.ZActivityOptions

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val registerWorkflow =
      ZWorkerFactory.newWorker(RecommendationsProcessorStarter.TaskQueue) @@
        ZWorker.addWorkflow[RecommendationsWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[ScheduledRecommendationsWorkflowImpl].fromClass @@
        ZWorker.addActivityImplementationService[ProcessorActivities] @@
        ZWorker.addActivityImplementationService[ContentFeedRecommendationEngine]

    val program = for {
      _    <- registerWorkflow
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      _    <- ZIO.serviceWithZIO[RecommendationsProcessorStarter](_.start(args.contains("reset")))
      _    <- ZWorkerFactory.serve
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        DatabaseMigrator.applyMigration,
        RecommendationsProcessorStarter.make,
        // dao
        SubscriberRepository.make,
        ContentFeedRepository.make,
        ContentFeedRecommendationRepository.make,
        PostgresQuill.make,
        Quill.DataSource.fromPrefix("db"),
        // activities
        ProcessorActivitiesImpl.make,
        NewsFeedRecommendationEngineImpl.make,
        // temporal
        ZWorkflowClient.make,
        ZActivityOptions.default,
        ZWorkflowServiceStubs.make,
        ZWorkerFactory.make,
        // options
        ZWorkflowServiceStubsOptions.make,
        ZWorkflowClientOptions.make @@
          ZWorkflowClientOptions.withDataConverter(ProtobufDataConverter.makeAutoLoad()),
        ZWorkerFactoryOptions.make
      )
      .withConfigProvider(
        ConfigProvider.defaultProvider orElse
          TypesafeConfigProvider.fromResourcePath()
      )

  }
}
