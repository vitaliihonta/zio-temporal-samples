package dev.vhonta.news.tgpush

import dev.vhonta.news.repository.{
  DatabaseMigrator,
  NewsFeedIntegrationRepository,
  NewsFeedRecommendationRepository,
  NewsFeedRepository,
  PostgresQuill,
  ReaderRepository
}
import io.getquill.jdbczio.Quill
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val program = ZIO.logInfo("Started Telegram push!")
    program
      .provide(
        DatabaseMigrator.applyMigration,
        TelegramModule.setupBot,
        // repository
        ReaderRepository.make,
        NewsFeedRepository.make,
        NewsFeedRecommendationRepository.make,
        NewsFeedIntegrationRepository.make,
        PostgresQuill.make,
        Quill.DataSource.fromPrefix("db")
      )
      .withConfigProvider(
        ConfigProvider.defaultProvider orElse
          TypesafeConfigProvider.fromResourcePath()
      )
  }
}
