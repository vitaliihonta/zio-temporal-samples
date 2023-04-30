package dev.vhonta.news.tgpush.bot

import java.time.{LocalDateTime, ZoneId}

object Shared {

  // TODO: tgDate is always UTC, need to ask users
  def getTimezone(tgDate: Int, now: LocalDateTime): ZoneId =
    ZoneId.of("UTC")
}
