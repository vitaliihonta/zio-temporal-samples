package dev.vhonta.content.tgbot.internal

import enumeratum.{Enum, EnumEntry}
import telegramium.bots.{BotCommand, InlineKeyboardButton}
import zio._

sealed trait Handle[-R]                     extends Product with Serializable
case object Unhandled                       extends Handle[Any]
case class Handled[-R](result: RIO[R, Any]) extends Handle[R]

trait TelegramHandler[-R, -In] { self =>

  def handle(in: In): RIO[R, Handle[R]]

  def orElse[R1 <: R, In1 <: In](that: TelegramHandler[R1, In1]): TelegramHandler[R1, In1] =
    new TelegramHandler.OrElse[R1, In1](self, that)
}

object TelegramHandler {
  def apply[R, In](thunk: In => RIO[R, Handle[R]]): TelegramHandler[R, In] =
    new TelegramHandler[R, In] {
      override def handle(in: In): RIO[R, Handle[R]] = thunk(in)
    }

  private[internal] final class OrElse[R, In](left: TelegramHandler[R, In], right: TelegramHandler[R, In])
      extends TelegramHandler[R, In] {

    override def handle(in: In): RIO[R, Handle[R]] =
      left.handle(in).flatMap {
        case Unhandled => right.handle(in)
        case handled   => ZIO.succeed(handled)
      }
  }
}

trait TelegramAction extends EnumEntry.Snakecase

trait TelegramCommandId extends TelegramAction {
  def description: String
}

trait TelegramCommandIdEnum[C <: TelegramCommandId] extends Enum[C] {
  def botCommands: List[BotCommand] = values.view.map { command =>
    BotCommand(command.entryName, command.description)
  }.toList
}

trait TelegramCallbackQueryId extends TelegramAction {
  def toInlineKeyboardButton(text: String): InlineKeyboardButton =
    InlineKeyboardButton(text, callbackData = Some(entryName))
}

trait TelegramCallbackQueryIdEnum[Q <: TelegramCallbackQueryId] extends Enum[Q]
