package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.Reader
import telegramium.bots.ParseMode
import zio.Task

trait NewsSyncBot {
  def start(): Task[Unit]

  def prepare(): Task[Unit]

  def notifyReader(reader: Reader, message: String, parseMode: Option[ParseMode]): Task[Unit]

  def pretendTyping(reader: Reader): Task[Unit]
}
