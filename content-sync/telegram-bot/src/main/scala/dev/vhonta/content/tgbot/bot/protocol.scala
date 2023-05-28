package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.tgbot.internal._
import telegramium.bots.InlineKeyboardButton

sealed abstract class ContentSyncCommand(val description: String) extends TelegramCommandId

object ContentSyncCommand extends TelegramCommandIdEnum[ContentSyncCommand] {
  case object Start       extends ContentSyncCommand("Start the bot")
  case object CreateTopic extends ContentSyncCommand("Create a topic")

  case object ListTopics extends ContentSyncCommand("List topics")

  case object ListIntegrations extends ContentSyncCommand("List integrations")

  case object LatestFeed extends ContentSyncCommand("Latest feed")

  case object GetSettings extends ContentSyncCommand("Get settings")

  case object UpdateSettings extends ContentSyncCommand("Update settings")

  override val values = findValues
}

object ContentSyncCallbackQuery {
  case object NewerMind    extends TelegramCallbackQuery.SimpleMatcher("nwm")
  case object SetupNewsApi extends TelegramCallbackQuery.SimpleMatcher("set_nw_api")

  case object SetupYoutube extends TelegramCallbackQuery.SimpleMatcher("set_ytbe")

  case object IntegrationDetails extends TelegramCallbackQuery.Matcher {
    override final type Data = Long /*integration id*/

    private val idRegex = "cfid/(\\d+)/in".r
    override def extract(callbackData: String): Option[Long] =
      callbackData match {
        case idRegex(integrationId) =>
          integrationId.toLongOption
        case _ => None
      }

    override def toInlineKeyboardButton(text: String, integrationId: Long): InlineKeyboardButton =
      InlineKeyboardButton(text, callbackData = Some(s"cfid/${integrationId}/in"))
  }

  case object DeleteIntegration extends TelegramCallbackQuery.Matcher {
    override final type Data = Long /*integration id*/

    private val idRegex = "cfid/(\\d+)/dl".r

    override def extract(callbackData: String): Option[Long] =
      callbackData match {
        case idRegex(integrationId) =>
          integrationId.toLongOption
        case _ => None
      }

    override def toInlineKeyboardButton(text: String, integrationId: Long): InlineKeyboardButton =
      InlineKeyboardButton(text, callbackData = Some(s"cfid/${integrationId}/dl"))
  }
}
