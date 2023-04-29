package dev.vhonta.news.tgpush

import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, ReaderRepository}
import org.http4s.blaze.client.BlazeClientBuilder
import telegramium.bots.high._
import zio._
import zio.interop.catz._

import java.net.InetSocketAddress

object TelegramModule {
  private val telegramApiConfig = Config.secret("bot-token").nested("telegram")

  private val makeApi: TaskLayer[Api[Task]] =
    ZLayer.scoped {
      ZIO.config(telegramApiConfig).flatMap { botToken =>
        for {
          _ <- ZIO.logInfo("Starting client...")
          addr <- ZIO.attemptBlocking(
                    new InetSocketAddress("api.telegram.org", 80)
                  )
          _    <- ZIO.logInfo(s"$addr addr=${addr.getAddress}")
          http <- BlazeClientBuilder[Task].resource.toScopedZIO
          _    <- ZIO.logInfo("Client started!")
          token = botToken.value.asString
        } yield BotApi(http, baseUrl = s"https://api.telegram.org/bot$token")
      }
    }

  val setupBot: ZLayer[ReaderRepository with NewsFeedIntegrationRepository, Throwable, Unit] =
    ZLayer.fromZIO {
      // never returns
      val setup = for {
        _   <- ZIO.logInfo("Starting the bot...")
        bot <- ZIO.service[NewsSyncBot]
        _   <- bot.prepare()
        _   <- bot.start()
      } yield ()

      setup.provideSome[ReaderRepository with NewsFeedIntegrationRepository](
        NewsSyncBot.make,
        makeApi
      )
    }
}
