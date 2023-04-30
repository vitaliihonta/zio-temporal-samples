package dev.vhonta.news.tgpush.internal

import enumeratum.{Enum, EnumEntry}
import telegramium.bots.{BotCommand, CallbackQuery, InlineKeyboardButton, Message}
import telegramium.bots.client.{Method, Methods}
import telegramium.bots.high.Api
import zio._
import zio.interop.catz._

trait TelegramAction extends EnumEntry.Snakecase

trait TelegramCommandId extends TelegramAction {
  def description: String
}

trait TelegramCommandIdEnum[C <: TelegramCommandId] extends Enum[C] {
  def botCommands: List[BotCommand] = values.view.map { command =>
    BotCommand(command.entryName, command.description)
  }.toList
}

trait TelegramQueryCallbackId extends TelegramAction {
  def toInlineKeyboardButton(text: String): InlineKeyboardButton =
    InlineKeyboardButton(text, callbackData = Some(entryName))
}

trait TelegramQueryCallbackIdEnum[Q <: TelegramQueryCallbackId] extends Enum[Q]

trait TelegramActionHandler[C <: TelegramAction, -R, In] extends PartialFunction[C, In => RIO[R, List[Method[_]]]] {
  self =>
}

trait TelegramCommandHandling[C <: TelegramCommandId] { this: Methods =>
  protected def api: Api[Task]

  // TODO: use partially applied
  def onCommand[R](
    command: C
  )(handler: Message => RIO[R, List[Method[_]]]
  ): TelegramActionHandler[C, R, Message] = new TelegramActionHandler[C, R, Message] {
    override def isDefinedAt(x: C): Boolean = x == command

    override def apply(v1: C): Message => RIO[R, List[Method[_]]] = handler
  }

  implicit class MessageCommandHandlers[R](handlers: List[TelegramActionHandler[C, R, Message]])(implicit e: Enum[C]) {
    def onMessage(msg: Message): Option[RIO[R, Any]] = {
      e.values
        .find(c => msg.text.exists(_.toLowerCase.startsWith("/" + c.entryName)))
        .flatMap { command =>
          println(s"Received command $command")
          handlers.foldLeft(Option.empty[RIO[R, List[Method[_]]]]) {
            case (None, handler) =>
              handler.lift(command).map(_.apply(msg))
            case (res @ Some(_), _) => res
          }
        }
        .map { handled =>
          handled.flatMap(methods => ZIO.foreach(methods)(method => api.execute(method)))
        }
    }
  }
}

trait TelegramQueryHandling[Q <: TelegramQueryCallbackId] {
  this: Methods =>
  protected def api: Api[Task]

  // TODO: use partially applied
  def onCallbackQuery[R](
    command: Q
  )(handler: CallbackQuery => RIO[R, List[Method[_]]]
  ): TelegramActionHandler[Q, R, CallbackQuery] = new TelegramActionHandler[Q, R, CallbackQuery] {
    override def isDefinedAt(x: Q): Boolean = x == command

    override def apply(v1: Q): CallbackQuery => RIO[R, List[Method[_]]] = handler
  }

  implicit class CallbackQueryCommandHandlers[R](
    handlers:   List[TelegramActionHandler[Q, R, CallbackQuery]]
  )(implicit e: Enum[Q]) {

    def onCallbackQuery(query: CallbackQuery): Option[RIO[R, Any]] = {
      e.values
        .find(c => query.data.exists(_.toLowerCase == c.entryName))
        .flatMap { command =>
          println(s"Received callback query $command")
          handlers.foldLeft(Option.empty[RIO[R, List[Method[_]]]]) {
            case (None, handler) =>
              handler.lift(command).map(_.apply(query))
            case (res @ Some(_), _) => res
          }
        }
        .map { handled =>
          handled.flatMap(methods => ZIO.foreach(methods)(method => api.execute(method)))
        }
    }
  }
}
