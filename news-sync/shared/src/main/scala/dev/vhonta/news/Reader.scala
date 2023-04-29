package dev.vhonta.news

import java.time.LocalDateTime
import java.util.UUID

case class Reader(
  id:             UUID,
  registeredAt:   LocalDateTime,
  telegramId:     Long,
  telegramChatId: Long)
