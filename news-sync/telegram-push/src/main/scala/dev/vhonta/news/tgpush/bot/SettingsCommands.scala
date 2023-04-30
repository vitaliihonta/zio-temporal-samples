package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.{NewsFeedIntegrationDetails, ReaderSettings}
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, ReaderRepository}
import dev.vhonta.news.tgpush.internal.{HandlingDSL, TelegramHandler}
import telegramium.bots.high.Api
import telegramium.bots.{ChatIntId, Html, Message}
import zio.{Task, ZIO}
import java.time.LocalTime

object SettingsCommands extends HandlingDSL {
  val onListIntegrations: TelegramHandler[Api[Task] with NewsFeedIntegrationRepository with ReaderRepository, Message] =
    onCommand(NewsSyncCommand.ListIntegrations) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          reader       <- Repositories.getReaderWithSettings(tgUser)
          integrations <- ZIO.serviceWithZIO[NewsFeedIntegrationRepository](_.findAllOwnedBy(reader.reader.id))
          integrationsStr = integrations.view
                              .sortBy(_.integration.`type`.entryName)
                              .map { integration =>
                                integration.integration match {
                                  case NewsFeedIntegrationDetails.NewsApi(apiKey) =>
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

  val onGetSettings: TelegramHandler[Api[Task] with ReaderRepository, Message] =
    onCommand(NewsSyncCommand.GetSettings) { msg =>
      ZIO
        .foreach(msg.from) { tgUser =>
          for {
            reader <- Repositories.getReaderWithSettings(tgUser)
            _      <- ZIO.logInfo(s"Getting settings reader=${reader.reader.id}")
            _ <- execute(
                   sendMessage(
                     chatId = ChatIntId(msg.chat.id),
                     text = s"Your current settings ⚙️:\n${settingsHtml(reader.settings)}\n",
                     parseMode = Some(Html)
                   )
                 )
          } yield ()
        }
    }

  // TODO: allows updating publishAt
  val onUpdateSettings: TelegramHandler[Api[Task] with ReaderRepository, Message] =
    onCommand(NewsSyncCommand.UpdateSettings) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          reader <- Repositories.getReaderWithSettings(tgUser)
          _      <- ZIO.logInfo(s"Updating settings reader=${reader.reader.id}")
          now    <- ZIO.clockWith(_.localDateTime)
          timezone = Shared.getTimezone(msg.date, now)
          updated <- ZIO.serviceWithZIO[ReaderRepository](
                       _.updateSettings(
                         readerId = reader.reader.id,
                         timezone = timezone,
                         publishAt = LocalTime.of(19, 0),
                         modifiedAt = now
                       )
                     )
          _ <- ZIO.foreach(updated) { updated =>
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

  val all: TelegramHandler[Api[Task] with NewsFeedIntegrationRepository with ReaderRepository, Message] =
    chain(
      onListIntegrations,
      onGetSettings,
      onUpdateSettings
    )

  private def settingsHtml(settings: ReaderSettings): String = {
    s"""\n<b>Timezone:</b> ${settings.timezone}\n<b>Publish at: ${settings.publishAt}</b>"""
  }

}
