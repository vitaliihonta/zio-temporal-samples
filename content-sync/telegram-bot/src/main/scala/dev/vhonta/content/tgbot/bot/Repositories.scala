package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.repository.{ContentFeedRepository, SubscriberRepository}
import dev.vhonta.content.{ContentFeedTopic, Subscriber, SubscriberWithSettings}
import telegramium.bots.{Chat, User}
import zio._
import java.sql.SQLException
import java.time.LocalTime
import java.util.UUID

object Repositories {

  def getOrCreateByTelegramId(
    tgUser: User,
    chat:   Chat,
    tgDate: Int
  ): RIO[SubscriberRepository, SubscriberWithSettings] =
    findByTelegramId(tgUser)
      .someOrElseZIO(Repositories.createSubscriber(tgUser, chat, tgDate))

  private def findByTelegramId(tgUser: User): RIO[SubscriberRepository, Option[SubscriberWithSettings]] =
    ZIO.serviceWithZIO[SubscriberRepository](
      _.findByTelegramId(tgUser.id)
    )

  private def createSubscriber(
    tgUser: User,
    chat:   Chat,
    tgDate: Int
  ): RIO[SubscriberRepository, SubscriberWithSettings] =
    for {
      _ <- ZIO.logInfo(s"Going to create a new subscriber ${tgUser.firstName} ${tgUser.lastName} id=${tgUser.id}")
      subscriberId <- ZIO.randomWith(_.nextUUID)
      now          <- ZIO.clockWith(_.localDateTime)
      timezone = Shared.getTimezone(tgDate, now)
      _ <- ZIO.logInfo(s"Guessed timezone=$timezone")
      subscriber <- ZIO.serviceWithZIO[SubscriberRepository](
                      _.create(
                        Subscriber(
                          id = subscriberId,
                          registeredAt = now,
                          telegramId = tgUser.id,
                          telegramChatId = chat.id
                        ),
                        timezone = timezone,
                        publishAt = LocalTime.of(19, 0)
                      )
                    )
    } yield subscriber

  def listTopics(
    subscribers: Option[Set[UUID]] = None
  ): ZIO[ContentFeedRepository, SQLException, List[ContentFeedTopic]] =
    ZIO.serviceWithZIO[ContentFeedRepository](_.listTopics(subscribers))
}
