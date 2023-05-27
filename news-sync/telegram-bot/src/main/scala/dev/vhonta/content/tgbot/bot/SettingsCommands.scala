package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.{ContentFeedIntegrationDetails, SubscriberSettings}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, SubscriberRepository}
import dev.vhonta.content.tgbot.internal.{HandlingDSL, TelegramHandler}
import telegramium.bots.high.Api
import telegramium.bots.{ChatIntId, Html, Message}
import zio.{Task, ZIO}
import java.time.LocalTime

object SettingsCommands extends HandlingDSL {
  val onListIntegrations
    : TelegramHandler[Api[Task] with ContentFeedIntegrationRepository with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.ListIntegrations) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          integrations <- ZIO.serviceWithZIO[ContentFeedIntegrationRepository](
                            _.findAllOwnedBy(subscriber.subscriber.id)
                          )
          integrationsStr = integrations.view
                              .sortBy(_.integration.`type`.entryName)
                              .map { integration =>
                                integration.integration match {
                                  // TODO: handle youtube
                                  case ContentFeedIntegrationDetails.NewsApi(apiKey) =>
                                    s""" #<b>${integration.id}</b> - <b>${integration.integration.`type`.entryName}</b>: <tg-spoiler>$apiKey</tg-spoiler>"""
                                }
                              }
                              .mkString("  \n")
          _ <- execute(
                 sendMessage(
                   chatId = ChatIntId(msg.chat.id),
                   text = s"Found the following integrations ℹ️:\n$integrationsStr",
                   parseMode = Some(Html)
                 )
               )
        } yield ()
      }
    }

  val onGetSettings: TelegramHandler[Api[Task] with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.GetSettings) { msg =>
      ZIO
        .foreach(msg.from) { tgUser =>
          for {
            subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
            _          <- ZIO.logInfo(s"Getting settings subscriber=${subscriber.subscriber.id}")
            _ <- execute(
                   sendMessage(
                     chatId = ChatIntId(msg.chat.id),
                     text = s"Your current settings ⚙️:\n${settingsHtml(subscriber.settings)}\n",
                     parseMode = Some(Html)
                   )
                 )
          } yield ()
        }
    }

  // TODO: allows updating publishAt
  val onUpdateSettings: TelegramHandler[Api[Task] with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.UpdateSettings) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          _          <- ZIO.logInfo(s"Updating settings subscriber=${subscriber.subscriber.id}")
          now        <- ZIO.clockWith(_.localDateTime)
          timezone = Shared.getTimezone(msg.date, now)
          updated <- ZIO.serviceWithZIO[SubscriberRepository](
                       _.updateSettings(
                         subscriber = subscriber.subscriber.id,
                         timezone = timezone,
                         publishAt = LocalTime.of(19, 0),
                         modifiedAt = now
                       )
                     )
          _ <- ZIO.foreachDiscard(updated) { updated =>
                 execute(
                   sendMessage(
                     chatId = ChatIntId(msg.chat.id),
                     text = s"Your new settings ⚙️:\n${settingsHtml(updated)}\n",
                     parseMode = Some(Html)
                   )
                 )
               }
        } yield ()
      }
    }

  val all: TelegramHandler[Api[Task] with ContentFeedIntegrationRepository with SubscriberRepository, Message] =
    chain(
      onListIntegrations,
      onGetSettings,
      onUpdateSettings
    )

  private def settingsHtml(settings: SubscriberSettings): String = {
    s"""\n<b>Timezone:</b> ${settings.timezone}\n<b>Publish at: ${settings.publishAt}</b>"""
  }

}
