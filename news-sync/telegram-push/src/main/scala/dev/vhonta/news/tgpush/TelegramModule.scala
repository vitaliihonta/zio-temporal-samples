package dev.vhonta.news.tgpush

import dev.vhonta.news.tgpush.bot.NewsSyncBot
import org.http4s.blaze.client.BlazeClientBuilder
import telegramium.bots.high._
import zio._
import zio.interop.catz._
import java.net.InetSocketAddress

object TelegramModule {

  val TaskQueue = "telegram-queue"

  private val telegramApiConfig = Config.secret("token").nested("telegram", "bot")

  val makeApi: TaskLayer[Api[Task]] =
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

  // never returns
  val serveBot: ZIO[NewsSyncBot, Throwable, Unit] =
    for {
      _   <- ZIO.logInfo("Starting the bot...")
      bot <- ZIO.service[NewsSyncBot]
      _   <- bot.prepare()
      _   <- bot.start()
    } yield ()
}
