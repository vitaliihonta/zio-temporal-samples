package dev.vhonta.content

import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.util.UUID

case class Subscriber(
  id:             UUID,
  registeredAt:   LocalDateTime,
  telegramId:     Long,
  telegramChatId: Long)

case class SubscriberSettings(
  subscriber: UUID,
  modifiedAt: LocalDateTime,
  timezone:   ZoneId,
  publishAt:  LocalTime)

case class SubscriberWithSettings(
  subscriber: Subscriber,
  settings:   SubscriberSettings)
