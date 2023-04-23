package dev.vhonta.news

import java.time.LocalDateTime
import java.util.UUID

// TODO: may need Telegram ID?
case class Reader(id: UUID, registeredAt: LocalDateTime)
