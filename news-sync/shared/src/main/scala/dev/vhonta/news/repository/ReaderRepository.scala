package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.{Reader, ReaderSettings, ReaderWithSettings}
import io.getquill.SnakeCase
import java.sql.SQLException
import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.util.UUID

object ReaderRepository {
  val make: URLayer[PostgresQuill[SnakeCase], ReaderRepository] =
    ZLayer.fromFunction(ReaderRepository(_: PostgresQuill[SnakeCase]))
}

case class ReaderRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._

  def create(reader: Reader, timezone: ZoneId, publishAt: LocalTime): Task[ReaderWithSettings] = {
    val settings = ReaderSettings(
      reader = reader.id,
      modifiedAt = reader.registeredAt,
      timezone = timezone,
      publishAt = publishAt
    )

    val insertReader = quote {
      query[Reader].insertValue(lift(reader))
    }

    val insertSettings = quote {
      query[ReaderSettings].insertValue(
        lift(settings)
      )
    }

    transaction(
      run(insertReader) *> run(insertSettings)
    ).as(ReaderWithSettings(reader, settings))
  }

  def updateSettings(
    readerId:   UUID,
    timezone:   ZoneId,
    publishAt:  LocalTime,
    modifiedAt: LocalDateTime
  ): Task[Option[ReaderSettings]] = {
    val update = quote {
      query[ReaderSettings]
        .filter(_.reader == lift(readerId))
        .update(
          _.timezone   -> lift(timezone),
          _.publishAt  -> lift(publishAt),
          _.modifiedAt -> lift(modifiedAt)
        )
    }

    run(update).map {
      case 0 => None
      case _ =>
        Some(
          ReaderSettings(
            reader = readerId,
            modifiedAt = modifiedAt,
            timezone = timezone,
            publishAt = publishAt
          )
        )
    }
  }

  def findById(readerId: UUID): IO[SQLException, Option[Reader]] = {
    val select = quote {
      query[Reader]
        .filter(_.id == lift(readerId))
        .take(1)
    }
    run(select).map(_.headOption)
  }

  def findByTelegramId(telegramId: Long): IO[SQLException, Option[ReaderWithSettings]] = {
    val select = quote {
      query[Reader]
        .filter(_.telegramId == lift(telegramId))
        .join(query[ReaderSettings])
        .on(_.id == _.reader)
        .map { case (reader, settings) => ReaderWithSettings(reader, settings) }
        .take(1)
    }
    run(select).map(_.headOption)
  }
}
