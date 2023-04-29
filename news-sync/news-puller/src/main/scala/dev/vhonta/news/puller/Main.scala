package dev.vhonta.news.puller

import dev.vhonta.news.puller.client.NewsApiClient
import dev.vhonta.news.puller.workflows._
import dev.vhonta.news.repository._
import io.getquill.jdbczio.Quill
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio._
import zio.logging.backend.SLF4J
import zio.temporal.activity.ZActivityOptions
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.config.typesafe.TypesafeConfigProvider

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val registerWorkflows =
      ZWorkerFactory.newWorker(NewsApiScheduledPullerStarter.TaskQueue) @@
        ZWorker.addWorkflow[NewsApiScheduledPullerWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[NewsApiPullTopicNewsWorkflowImpl].fromClass @@
        ZWorker.addActivityImplementationService[DatabaseActivities] @@
        ZWorker.addActivityImplementationService[NewsActivities]

    val program = for {
      _    <- registerWorkflows
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      _    <- ZIO.serviceWithZIO[NewsApiScheduledPullerStarter](_.start(args.contains("reset")))
      _    <- ZWorkerFactory.serve
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        DatabaseMigrator.applyMigration,
        NewsApiScheduledPullerStarter.make,
        // http
        HttpClientZioBackend.layer(),
        NewsApiClient.make,
        // dao
        NewsFeedRepository.make,
        NewsFeedIntegrationRepository.make,
        PostgresQuill.make,
        Quill.DataSource.fromPrefix("db"),
        // activities
        DatabaseActivitiesImpl.make,
        NewsActivitiesImpl.make,
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
