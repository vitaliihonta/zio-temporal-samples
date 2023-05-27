package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.{ContentFeedIntegrationDetails, SubscriberSettings}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, SubscriberRepository}
import dev.vhonta.content.tgbot.internal.{HandlingDSL, TelegramHandler}
import telegramium.bots.high.Api
import telegramium.bots.{CallbackQuery, ChatIntId, Html, InlineKeyboardButton, InlineKeyboardMarkup, Message}
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
          integrationsButtons = integrations
                                  .groupBy(_.integration.`type`)
                                  .view
                                  .map { case (_, integrations) =>
                                    integrations.map { integration =>
                                      ContentSyncCallbackQuery.IntegrationDetails.toInlineKeyboardButton(
                                        text = s"#${integration.id} - ${integration.integration.`type`}",
                                        integrationId = integration.id
                                      )
                                    }
                                  }
                                  .toList
          _ <- execute(
                 sendMessage(
                   chatId = ChatIntId(msg.chat.id),
                   text = s"Found the following integrations ℹ️",
                   parseMode = Some(Html),
                   replyMarkup = Some(
                     InlineKeyboardMarkup(integrationsButtons)
                   )
                 )
               )
        } yield ()
      }
    }

  val onGetIntegrationDetails: TelegramHandler[Api[Task] with ContentFeedIntegrationRepository, CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.IntegrationDetails) { (query, integrationId) =>
      ZIO.foreach(query.message) { msg =>
        for {
          integration <- ZIO.serviceWithZIO[ContentFeedIntegrationRepository](
                           _.findById(integrationId)
                         )
          _ <- ZIO
                 .foreach(integration) { integration =>
                   val text = integration.integration match {
                     case ContentFeedIntegrationDetails.NewsApi(apiKey) =>
                       s""" #<b>${integration.id}</b> - <b>${integration.integration.`type`.entryName}</b>: <tg-spoiler>$apiKey</tg-spoiler>"""
                     case ContentFeedIntegrationDetails.Youtube(_, refreshToken, _, _) =>
                       s""" #<b>${integration.id}</b> - <b>${integration.integration.`type`.entryName}</b>: <tg-spoiler>$refreshToken</tg-spoiler>"""
                   }
                   val markup = InlineKeyboardMarkup(
                     List(
                       List(
                         ContentSyncCallbackQuery.DeleteIntegration.toInlineKeyboardButton("Delete", integrationId)
                       )
                     )
                   )
                   execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = text,
                       parseMode = Some(Html),
                       replyMarkup = Some(markup)
                     )
                   )
                 }
                 .someOrElseZIO(
                   execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = s"Integration #$integrationId <b>NOT FOUND</b>",
                       parseMode = Some(Html)
                     )
                   )
                 )
        } yield ()
      }
    }

  val onDeleteIntegration: TelegramHandler[Api[Task] with ContentFeedIntegrationRepository, CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.DeleteIntegration) { (query, integrationId) =>
      ZIO.foreach(query.message) { msg =>
        for {
          integration <- ZIO.serviceWithZIO[ContentFeedIntegrationRepository](
                           _.findById(integrationId)
                         )
          _ <- ZIO
                 .foreach(integration) { integration =>
                   ZIO.serviceWithZIO[ContentFeedIntegrationRepository](
                     _.deleteById(integrationId)
                   ) *>
                     execute(
                       sendMessage(
                         chatId = ChatIntId(msg.chat.id),
                         text = s"Integration #$integrationId (${integration.integration.`type`}) <b>DELETED</b>",
                         parseMode = Some(Html)
                       )
                     )
                 }
                 .someOrElseZIO(
                   execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = s"Integration #$integrationId <b>NOT FOUND</b>",
                       parseMode = Some(Html)
                     )
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

  val messageHandlers
    : TelegramHandler[Api[Task] with ContentFeedIntegrationRepository with SubscriberRepository, Message] =
    chain(
      onListIntegrations,
      onGetSettings,
      onUpdateSettings
    )

  val callbackQueryHandlers: TelegramHandler[Api[Task] with ContentFeedIntegrationRepository, CallbackQuery] =
    chain(
      onGetIntegrationDetails,
      onDeleteIntegration
    )

  private def settingsHtml(settings: SubscriberSettings): String = {
    s"""\n<b>Timezone:</b> ${settings.timezone}\n<b>Publish at: ${settings.publishAt}</b>"""
  }

}
