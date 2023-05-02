package dev.vhonta.news.tgpush

import dev.vhonta.news.client.NewsApiClient
import dev.vhonta.news.repository.{
  DatabaseMigrator,
  NewsFeedIntegrationRepository,
  NewsFeedRecommendationRepository,
  NewsFeedRepository,
  PostgresQuill,
  ReaderRepository
}
import dev.vhonta.news.tgpush.bot.NewsSyncBotImpl
import dev.vhonta.news.tgpush.workflow.{
  AddTopicWorkflowImpl,
  NewsApiActivities,
  NewsApiActivitiesImpl,
  NewsFeedActivities,
  NewsFeedActivitiesImpl,
  OnDemandPushRecommendationsWorkflowImpl,
  PushRecommendationsWorkflowImpl,
  ScheduledPushRecommendationsWorkflowImpl,
  SetupNewsApiWorkflowImpl,
  TelegramActivities,
  TelegramActivitiesImpl
}
import io.getquill.jdbczio.Quill
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J
import zio.temporal.activity.ZActivityOptions
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker._
import zio.temporal.workflow._

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val registerWorkflows =
      ZWorkerFactory.newWorker(TelegramModule.TaskQueue) @@
        ZWorker.addWorkflow[SetupNewsApiWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[AddTopicWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[ScheduledPushRecommendationsWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[OnDemandPushRecommendationsWorkflowImpl].fromClass @@
        ZWorker.addWorkflow[PushRecommendationsWorkflowImpl].fromClass @@
        ZWorker.addActivityImplementationService[NewsApiActivities] @@
        ZWorker.addActivityImplementationService[TelegramActivities] @@
        ZWorker.addActivityImplementationService[NewsFeedActivities]

    val program = for {
      _    <- ZIO.logInfo("Started Telegram push!")
      _    <- registerWorkflows
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      _    <- ZIO.serviceWithZIO[ScheduledPushStarter](_.start(args.contains("reset")))
      _    <- ZWorkerFactory.setup
      _    <- TelegramModule.serveBot
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        DatabaseMigrator.applyMigration,
        // http
        HttpClientZioBackend.layer(),
        NewsApiClient.make,
        // repository
        ReaderRepository.make,
        NewsFeedRepository.make,
        NewsFeedRecommendationRepository.make,
        NewsFeedIntegrationRepository.make,
        PostgresQuill.make,
        Quill.DataSource.fromPrefix("db"),
        // telegram
        TelegramModule.makeApi,
        NewsSyncBotImpl.make,
        // activities
        NewsApiActivitiesImpl.make,
        TelegramActivitiesImpl.make,
        NewsFeedActivitiesImpl.make,
        // temporal
        ScheduledPushStarter.make,
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
