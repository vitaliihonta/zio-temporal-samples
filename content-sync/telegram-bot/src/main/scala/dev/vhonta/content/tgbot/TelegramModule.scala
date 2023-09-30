package dev.vhonta.content.tgbot

import dev.vhonta.content.tgbot.bot.ContentSyncBot
import dev.vhonta.content.tgbot.workflow.common.{ContentFeedActivitiesImpl, TelegramActivitiesImpl}
import dev.vhonta.content.tgbot.workflow.{AddTopicWorkflowImpl, NewsApiActivitiesImpl, YoutubeActivitiesImpl}
import dev.vhonta.content.tgbot.workflow.push.{
  OnDemandPushRecommendationsWorkflowImpl,
  PushConfigurationActivitiesImpl,
  PushRecommendationsWorkflowImpl,
  ScheduledPushRecommendationsWorkflowImpl
}
import dev.vhonta.content.tgbot.workflow.setup.{SetupNewsApiWorkflowImpl, SetupYoutubeWorkflowImpl}
import org.http4s.blaze.client.BlazeClientBuilder
import telegramium.bots.high._
import zio._
import zio.interop.catz._
import zio.temporal.activity.ZActivityImplementationObject
import zio.temporal.workflow.ZWorkflowImplementationClass

import java.net.InetSocketAddress

object TelegramModule {

  val TaskQueue = "telegram-queue"

  private val telegramApiConfig = Config.secret("token").nested("telegram", "bot")

  val workflows: List[ZWorkflowImplementationClass[_]] = List(
    ZWorkflowImplementationClass[SetupNewsApiWorkflowImpl],
    ZWorkflowImplementationClass[SetupYoutubeWorkflowImpl],
    ZWorkflowImplementationClass[AddTopicWorkflowImpl],
    ZWorkflowImplementationClass[ScheduledPushRecommendationsWorkflowImpl],
    ZWorkflowImplementationClass[OnDemandPushRecommendationsWorkflowImpl],
    ZWorkflowImplementationClass[PushRecommendationsWorkflowImpl]
  )

  val activitiesLayer = ZLayer.collectAll(
    List(
      ZActivityImplementationObject.layer(NewsApiActivitiesImpl.make),
      ZActivityImplementationObject.layer(YoutubeActivitiesImpl.make),
      ZActivityImplementationObject.layer(TelegramActivitiesImpl.make),
      ZActivityImplementationObject.layer(ContentFeedActivitiesImpl.make),
      ZActivityImplementationObject.layer(PushConfigurationActivitiesImpl.make)
    )
  )

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
  val serveBot: ZIO[ContentSyncBot, Throwable, Nothing] = {
    ZIO.serviceWithZIO[ContentSyncBot] { bot =>
      (for {
        _ <- ZIO.logInfo("Starting the bot...")
        _ <- bot.prepare()
        _ <- bot.start()
      } yield ()) *> ZIO.never
    }
  }
}
