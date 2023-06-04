package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.tgbot.internal.{HandlingDSL, TelegramHandler}
import telegramium.bots.{CallbackQuery, Message}
import telegramium.bots.high.Api
import zio.Task

trait BaseCommandHandler extends HandlingDSL {
  def messageHandlers: TelegramHandler[Api[Task], Message]

  def callbackQueryHandlers: TelegramHandler[Api[Task], CallbackQuery]
}
