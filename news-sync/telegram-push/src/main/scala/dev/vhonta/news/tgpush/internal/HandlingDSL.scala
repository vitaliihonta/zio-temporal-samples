package dev.vhonta.news.tgpush.internal

import telegramium.bots.{CallbackQuery, Message}
import telegramium.bots.client.{Method, Methods}
import telegramium.bots.high.Api
import zio._

trait HandlingDSL extends Methods {
  def execute[Res](method: Method[Res]): RIO[Api[Task], Res] =
    ZIO.serviceWithZIO[Api[Task]](_.execute(method))

  def onMessage[R](thunk: Message => RIO[R, Handle[R]]): TelegramHandler[R, Message] =
    TelegramHandler[R, Message](thunk)

  def onCommand[R, C <: TelegramCommandId](c: C)(thunk: Message => RIO[R, Any]): TelegramHandler[R, Message] =
    onMessage(msg =>
      ZIO
        .foreach(msg.text)(text =>
          ZIO.when(text.toLowerCase.startsWith("/" + c.entryName))(
            ZIO.succeed(Handled[R](thunk(msg)))
          )
        )
        .map(_.flatten.getOrElse(Unhandled))
    )

  def onCallbackQuery[R](thunk: CallbackQuery => RIO[R, Handle[R]]): TelegramHandler[R, CallbackQuery] =
    TelegramHandler[R, CallbackQuery](thunk)

  def onCallbackQueryId[R, C <: TelegramCallbackQueryId](
    c:     C
  )(thunk: CallbackQuery => RIO[R, Any]
  ): TelegramHandler[R, CallbackQuery] =
    onCallbackQuery(query =>
      ZIO
        .foreach(query.data)(data =>
          ZIO.when(data.toLowerCase == c.entryName)(
            ZIO.succeed(Handled[R](thunk(query)))
          )
        )
        .map(_.flatten.getOrElse(Unhandled))
    )

  def chain[R, In](
    first:  TelegramHandler[R, In],
    second: TelegramHandler[R, In],
    rest:   TelegramHandler[R, In]*
  ): TelegramHandler[R, In] =
    (first :: second :: rest.toList).reduce(_ orElse _)

  def whenSome[A](opt: Option[A]): WhenSomePartiallyApplied[Any, A] =
    whenSomeZIO(ZIO.succeed(opt))

  def whenSomeZIO[R, A](rioOpt: RIO[R, Option[A]]): WhenSomePartiallyApplied[R, A] =
    new WhenSomePartiallyApplied(rioOpt)

  def handled[R](thunk: RIO[R, Any]): RIO[R, Handle[R]] =
    ZIO.succeed(Handled(thunk))

  def unhandled: RIO[Any, Handle[Any]] =
    ZIO.succeed(Unhandled)

  final class WhenSomePartiallyApplied[R, A](rioOpt: RIO[R, Option[A]]) {
    def apply[R1 <: R](thunk: A => RIO[R1, Handle[R1]]): RIO[R1, Handle[R1]] =
      rioOpt.flatMap {
        case None        => ZIO.succeed(Unhandled)
        case Some(value) => thunk(value)
      }
  }

  implicit class HandleOps[R](handlingResult: RIO[R, Handle[R]]) {
    def handleOrElseZIO[R1 <: R](that: RIO[R1, Any]): RIO[R1, Any] =
      handlingResult.flatMap {
        case Unhandled      => that
        case Handled(thunk) => thunk
      }

    def handleOrIgnore: RIO[R, Any] =
      handleOrElseZIO(ZIO.unit)
  }
}
