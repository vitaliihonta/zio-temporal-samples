package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.tgpush.internal._

sealed abstract class NewsSyncCommand(val description: String) extends TelegramCommandId

object NewsSyncCommand extends TelegramCommandIdEnum[NewsSyncCommand] {
  case object Start       extends NewsSyncCommand("Start the bot")
  case object CreateTopic extends NewsSyncCommand("Create a topic")

  case object ListTopics extends NewsSyncCommand("List topics")

  case object ListIntegrations extends NewsSyncCommand("List integrations")

  case object GetSettings extends NewsSyncCommand("Get settings")

  case object UpdateSettings extends NewsSyncCommand("Update settings")

  override val values = findValues
}

sealed trait NewsSyncCallbackQuery extends TelegramCallbackQueryId
object NewsSyncCallbackQuery extends TelegramCallbackQueryIdEnum[NewsSyncCallbackQuery] {
  case object NewerMind    extends NewsSyncCallbackQuery
  case object SetupNewsApi extends NewsSyncCallbackQuery

  override val values = findValues
}
