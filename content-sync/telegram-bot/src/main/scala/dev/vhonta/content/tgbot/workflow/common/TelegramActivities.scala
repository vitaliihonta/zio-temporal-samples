package dev.vhonta.content.tgbot.workflow.common

import dev.vhonta.content.repository.SubscriberRepository
import dev.vhonta.content.tgbot.bot.ContentSyncBot
import dev.vhonta.content.tgbot.proto.{NotifySubscriberParams, PretendTypingParams, TelegramParseMode}
import telegramium.bots
import telegramium.bots.{InlineKeyboardButton, InlineKeyboardMarkup}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

import java.util.UUID

@activityInterface
trait TelegramActivities {
  @throws[SubscriberNotFoundException]
  def notifySubscriber(params: NotifySubscriberParams): Unit

  @throws[SubscriberNotFoundException]
  def pretendTyping(params: PretendTypingParams): Unit
}

case class SubscriberNotFoundException(subscriberId: UUID)
    extends Exception(s"Subscriber with id=$subscriberId not found")

object TelegramActivitiesImpl {
  val make: URLayer[SubscriberRepository with ContentSyncBot with ZActivityOptions[Any], TelegramActivities] =
    ZLayer.fromFunction(TelegramActivitiesImpl(_: SubscriberRepository, _: ContentSyncBot)(_: ZActivityOptions[Any]))
}

case class TelegramActivitiesImpl(
  subscriberRepository: SubscriberRepository,
  bot:                  ContentSyncBot
)(implicit options:     ZActivityOptions[Any])
    extends TelegramActivities {

  override def notifySubscriber(params: NotifySubscriberParams): Unit =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Notifying subscriber=${params.subscriber.fromProto} about the status")
        subscriber <- subscriberRepository
                        .findById(params.subscriber.fromProto)
                        .someOrFail(SubscriberNotFoundException(params.subscriber.fromProto))
        _ <- ZIO.logInfo("Pushing a telegram notification...")
        parseMode = params.parseMode.flatMap {
                      case TelegramParseMode.Html            => Some(bots.Html)
                      case TelegramParseMode.Markdown2       => Some(bots.Markdown2)
                      case TelegramParseMode.Unrecognized(_) => None
                    }
        _ <- bot.notifySubscriber(
               subscriber = subscriber,
               message = params.message,
               parseMode = parseMode,
               inlineKeyboardMarkup = params.replyMarkup.map(markup =>
                 InlineKeyboardMarkup(
                   markup.groups.view
                     .map(
                       _.buttons.view
                         .map(button =>
                           InlineKeyboardButton(
                             text = button.text,
                             url = button.url,
                             callbackData = button.callbackData
                           )
                         )
                         .toList
                     )
                     .toList
                 )
               )
             )
      } yield ()
    }

  override def pretendTyping(params: PretendTypingParams): Unit =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Pretend typing for subscriber=${params.subscriber.fromProto}")
        subscriber <- subscriberRepository
                        .findById(params.subscriber.fromProto)
                        .someOrFail(SubscriberNotFoundException(params.subscriber.fromProto))
        _ <- bot.pretendTyping(subscriber)
      } yield ()
    }
}
