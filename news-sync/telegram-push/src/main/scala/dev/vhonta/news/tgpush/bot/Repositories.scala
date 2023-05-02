package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.{NewsFeedIntegration, NewsFeedTopic, Reader, ReaderWithSettings}
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, NewsFeedRepository, ReaderRepository}
import telegramium.bots.{Chat, User}
import zio.{IO, RIO, ZIO}

import java.sql.SQLException
import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.util.UUID

object Repositories {

  def getOrCreateByTelegramId(tgUser: User, chat: Chat, tgDate: Int): RIO[ReaderRepository, ReaderWithSettings] =
    findByTelegramId(tgUser)
      .someOrElseZIO(Repositories.createReader(tgUser, chat, tgDate))

  private def findByTelegramId(tgUser: User): RIO[ReaderRepository, Option[ReaderWithSettings]] =
    ZIO.serviceWithZIO[ReaderRepository](
      _.findByTelegramId(tgUser.id)
    )

  private def createReader(tgUser: User, chat: Chat, tgDate: Int): RIO[ReaderRepository, ReaderWithSettings] =
    for {
      _        <- ZIO.logInfo(s"Going to create a new reader ${tgUser.firstName} ${tgUser.lastName} id=${tgUser.id}")
      readerId <- ZIO.randomWith(_.nextUUID)
      now      <- ZIO.clockWith(_.localDateTime)
      timezone = Shared.getTimezone(tgDate, now)
      _ <- ZIO.logInfo(s"Guessed timezone=$timezone")
      reader <- ZIO.serviceWithZIO[ReaderRepository](
                  _.create(
                    Reader(
                      id = readerId,
                      registeredAt = now,
                      telegramId = tgUser.id,
                      telegramChatId = chat.id
                    ),
                    timezone = timezone,
                    publishAt = LocalTime.of(19, 0)
                  )
                )
    } yield reader

  def listTopics(readers: Option[Set[UUID]] = None): ZIO[NewsFeedRepository, SQLException, List[NewsFeedTopic]] =
    ZIO.serviceWithZIO[NewsFeedRepository](_.listTopics(readers))
}
