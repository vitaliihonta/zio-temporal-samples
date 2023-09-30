package dev.vhonta.content.tgbot

import dev.vhonta.content.newsapi.NewsApiClient
import dev.vhonta.content.repository.{
  ContentFeedIntegrationRepository,
  ContentFeedRecommendationRepository,
  ContentFeedRepository,
  DatabaseMigrator,
  PostgresQuill,
  SubscriberRepository
}
import dev.vhonta.content.tgbot.api.YoutubeCallbackHandlingApi
import dev.vhonta.content.tgbot.bot.{
  ContentSyncBotImpl,
  SettingsCommandsHandler,
  SetupIntegrationHandlers,
  SubscribersService,
  TopicsCommandHandler
}
import dev.vhonta.content.youtube.{GoogleModule, OAuth2Client, YoutubeClient}
import io.getquill.jdbczio.Quill
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J
import zio.temporal.activity.ZActivityRunOptions
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.schedules.{ZScheduleClient, ZScheduleClientOptions}
import zio.temporal.worker._
import zio.temporal.workflow._

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val registerWorkflows =
      ZWorkerFactory.newWorker(TelegramModule.TaskQueue) @@
        ZWorker.addWorkflowImplementations(TelegramModule.workflows) @@
        ZWorker.addActivityImplementationsLayer(TelegramModule.activitiesLayer)

    val program = for {
      _    <- ZIO.logInfo("Started Telegram push!")
      _    <- registerWorkflows
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      _    <- ZIO.serviceWithZIO[ScheduledPushStarter](_.start(args.contains("reset")))
      _    <- ZWorkerFactory.setup
      _    <- TelegramModule.serveBot.fork // never returns
      _    <- HttpApiModule.serveApi
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        DatabaseMigrator.applyMigration,
        // http
        HttpClientZioBackend.layer(),
        NewsApiClient.make,
        OAuth2Client.make,
        YoutubeClient.make,
        GoogleModule.make,
        YoutubeCallbackHandlingApi.make,
        // repository
        SubscriberRepository.make,
        ContentFeedRepository.make,
        ContentFeedRecommendationRepository.make,
        ContentFeedIntegrationRepository.make,
        PostgresQuill.make,
        Quill.DataSource.fromPrefix("db"),
        // telegram
        TelegramModule.makeApi,
        ContentSyncBotImpl.make,
        SetupIntegrationHandlers.make,
        SettingsCommandsHandler.make,
        TopicsCommandHandler.make,
        SubscribersService.make,
        // temporal
        ScheduledPushStarter.make,
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
