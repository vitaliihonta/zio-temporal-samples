package dev.vhonta.news

import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.util.UUID

case class Reader(
  id:             UUID,
  registeredAt:   LocalDateTime,
  telegramId:     Long,
  telegramChatId: Long)

case class ReaderSettings(
  reader:     UUID,
  modifiedAt: LocalDateTime,
  timezone:   ZoneId,
  publishAt:  LocalTime)

case class ReaderWithSettings(
  reader:   Reader,
  settings: ReaderSettings)
