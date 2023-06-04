package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.Subscriber
import telegramium.bots.{ParseMode, InlineKeyboardMarkup}
import zio.Task

trait ContentSyncBot {
  def start(): Task[Unit]

  def prepare(): Task[Unit]

  def notifySubscriber(
    subscriber:           Subscriber,
    message:              String,
    parseMode:            Option[ParseMode],
    inlineKeyboardMarkup: Option[InlineKeyboardMarkup]
  ): Task[Unit]

  def pretendTyping(subscriber: Subscriber): Task[Unit]
}
