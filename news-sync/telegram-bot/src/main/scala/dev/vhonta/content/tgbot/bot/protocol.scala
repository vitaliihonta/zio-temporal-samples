package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.tgbot.internal._

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

sealed trait ContentSyncCallbackQuery extends TelegramCallbackQueryId
object ContentSyncCallbackQuery extends TelegramCallbackQueryIdEnum[ContentSyncCallbackQuery] {
  case object NewerMind    extends ContentSyncCallbackQuery
  case object SetupNewsApi extends ContentSyncCallbackQuery

  override val values = findValues
}
