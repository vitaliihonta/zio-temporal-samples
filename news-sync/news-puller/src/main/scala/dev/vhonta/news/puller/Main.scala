package dev.vhonta.news.puller

import dev.vhonta.news.puller.client.NewsApiClient
import dev.vhonta.news.puller.workflows.{
  DatabaseActivities,
  DatabaseActivitiesImpl,
  NewsActivities,
  NewsActivitiesImpl,
  PullTopicNewsWorkflowImpl,
  ScheduledPullerStarter,
  ScheduledPullerWorkflowImpl
}
import dev.vhonta.news.repository.{DatabaseMigrator, NewsFeedRepository}
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio._
import zio.logging.backend.SLF4J
import zio.temporal.activity.ZActivityOptions
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker.{ZWorker, ZWorkerFactory, ZWorkerFactoryOptions}
import zio.temporal.workflow.{
  ZWorkflowClient,
  ZWorkflowClientOptions,
  ZWorkflowServiceStubs,
  ZWorkflowServiceStubsOptions
}
import zio.config.typesafe.TypesafeConfigProvider

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val registerWorkflows =
      ZWorkerFactory.newWorker(ScheduledPullerStarter.TaskQueue) @@
        ZWorker.addWorkflow[ScheduledPullerWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[PullTopicNewsWorkflowImpl].fromClass @@
        ZWorker.addActivityImplementationService[DatabaseActivities] @@
        ZWorker.addActivityImplementationService[NewsActivities]

    val program = for {
      _    <- registerWorkflows
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      _    <- ZIO.serviceWithZIO[ScheduledPullerStarter](_.start(args.contains("reset")))
      _    <- ZWorkerFactory.serve
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        DatabaseMigrator.applyMigration,
        ScheduledPullerStarter.make,
        // http
        HttpClientZioBackend.layer(),
        NewsApiClient.make,
        // dao
        NewsFeedRepository.make,
        Quill.Postgres.fromNamingStrategy(SnakeCase),
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
