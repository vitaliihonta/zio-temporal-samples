package dev.vhonta.content.puller

import dev.vhonta.content.newsapi.NewsApiClient
import dev.vhonta.content.puller.workflows._
import dev.vhonta.content.puller.workflows.newsapi.{NewsActivities, NewsActivitiesImpl, NewsApiModule}
import dev.vhonta.content.puller.workflows.youtube.{YoutubeActivities, YoutubeActivitiesImpl, YoutubeModule}
import dev.vhonta.content.repository._
import dev.vhonta.content.youtube.{GoogleModule, OAuth2Client, YoutubeClient}
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
    val registerWorkers = {
      ZIO.collectAllDiscard(
        List(
          NewsApiModule.worker,
          YoutubeModule.worker
        )
      )
    }

    val program = for {
      _    <- registerWorkers
      _    <- ZWorkflowServiceStubs.setup()
      args <- getArgs
      reset = args.contains("reset")
      _ <- NewsApiModule.start(reset)
      _ <- YoutubeModule.start(reset)
      _ <- ZWorkerFactory.serve
    } yield ()

    program
      .provideSome[ZIOAppArgs with Scope](
        DatabaseMigrator.applyMigration,
        // http
        HttpClientZioBackend.layer(),
        NewsApiClient.make,
        YoutubeClient.make,
        OAuth2Client.make,
        GoogleModule.make,
        // dao
        ContentFeedRepository.make,
        ContentFeedIntegrationRepository.make,
        PostgresQuill.make,
        Quill.DataSource.fromPrefix("db"),
        // activities
        DatabaseActivitiesImpl.make,
        NewsActivitiesImpl.make,
        YoutubeActivitiesImpl.make,
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
