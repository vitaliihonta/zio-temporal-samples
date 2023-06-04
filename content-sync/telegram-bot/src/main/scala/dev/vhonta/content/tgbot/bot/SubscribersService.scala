package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.repository.SubscriberRepository
import dev.vhonta.content.{Subscriber, SubscriberSettings, SubscriberWithSettings}
import telegramium.bots.{Chat, User}
import zio._

import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.util.UUID

object SubscribersService {
  val make: URLayer[SubscriberRepository, SubscribersService] =
    ZLayer.fromFunction(SubscribersService(_))
}

case class SubscribersService(
  subscriberRepository: SubscriberRepository) {

  def getOrCreateByTelegramId(
    tgUser: User,
    chat:   Chat,
    tgDate: Int
  ): Task[SubscriberWithSettings] =
    subscriberRepository
      .findByTelegramId(tgUser.id)
      .someOrElseZIO(createSubscriber(tgUser, chat, tgDate))

  def updateSettings(
    subscriber: UUID,
    timezone:   ZoneId,
    publishAt:  LocalTime,
    modifiedAt: LocalDateTime
  ): Task[Option[SubscriberSettings]] =
    subscriberRepository.updateSettings(subscriber, timezone, publishAt, modifiedAt)

  private def createSubscriber(
    tgUser: User,
    chat:   Chat,
    tgDate: Int
  ): Task[SubscriberWithSettings] =
    for {
      _ <- ZIO.logInfo(s"Going to create a new subscriber ${tgUser.firstName} ${tgUser.lastName} id=${tgUser.id}")
      subscriberId <- ZIO.randomWith(_.nextUUID)
      now          <- ZIO.clockWith(_.localDateTime)
      timezone = Shared.getTimezone(tgDate, now)
      _ <- ZIO.logInfo(s"Guessed timezone=$timezone")
      subscriber <- subscriberRepository.create(
                      Subscriber(
                        id = subscriberId,
                        registeredAt = now,
                        telegramId = tgUser.id,
                        telegramChatId = chat.id
                      ),
                      timezone = timezone,
                      publishAt = LocalTime.of(19, 0)
                    )
    } yield subscriber
}
