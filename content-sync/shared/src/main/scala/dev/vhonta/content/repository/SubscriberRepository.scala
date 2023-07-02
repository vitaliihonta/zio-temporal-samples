package dev.vhonta.content.repository

import zio._
import dev.vhonta.content.{Subscriber, SubscriberSettings, SubscriberWithSettings}
import io.getquill.SnakeCase
import java.sql.SQLException
import java.time.{LocalDateTime, LocalTime, ZoneId}
import java.util.UUID

object SubscriberRepository {
  val make: URLayer[PostgresQuill[SnakeCase], SubscriberRepository] =
    ZLayer.fromFunction(SubscriberRepository(_: PostgresQuill[SnakeCase]))
}

case class SubscriberRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._
  import quill.extras._

  def listAllForPublish(currentTime: LocalTime, deltaMinutes: Long): IO[SQLException, List[SubscriberWithSettings]] = {
    val startTime = currentTime.minusMinutes(deltaMinutes)

    val select = quote {
      query[Subscriber]
        .join(
          query[SubscriberSettings]
            .filter(_.publishAt >= lift(startTime))
            .filter(_.publishAt < lift(currentTime))
        )
        .on(_.id == _.subscriber)
        .map { case (subscriber, settings) => SubscriberWithSettings(subscriber, settings) }
    }
    run(select)
  }

  def create(subscriber: Subscriber, timezone: ZoneId, publishAt: LocalTime): Task[SubscriberWithSettings] = {
    val settings = SubscriberSettings(
      subscriber = subscriber.id,
      modifiedAt = subscriber.registeredAt,
      timezone = timezone,
      publishAt = publishAt
    )

    val insertSubscriber = quote {
      query[Subscriber].insertValue(lift(subscriber))
    }

    val insertSettings = quote {
      query[SubscriberSettings].insertValue(
        lift(settings)
      )
    }

    transaction(
      run(insertSubscriber) *> run(insertSettings)
    ).as(SubscriberWithSettings(subscriber, settings))
  }

  def updateSettings(
    subscriber: UUID,
    timezone:   ZoneId,
    publishAt:  LocalTime,
    modifiedAt: LocalDateTime
  ): Task[Option[SubscriberSettings]] = {
    val update = quote {
      query[SubscriberSettings]
        .filter(_.subscriber == lift(subscriber))
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
          SubscriberSettings(
            subscriber = subscriber,
            modifiedAt = modifiedAt,
            timezone = timezone,
            publishAt = publishAt
          )
        )
    }
  }

  def findById(subscriber: UUID): IO[SQLException, Option[Subscriber]] = {
    val select = quote {
      query[Subscriber]
        .filter(_.id == lift(subscriber))
        .take(1)
    }
    run(select).map(_.headOption)
  }

  def findByTelegramId(telegramId: Long): IO[SQLException, Option[SubscriberWithSettings]] = {
    val select = quote {
      query[Subscriber]
        .filter(_.telegramId == lift(telegramId))
        .join(query[SubscriberSettings])
        .on(_.id == _.subscriber)
        .map { case (subscriber, settings) => SubscriberWithSettings(subscriber, settings) }
        .take(1)
    }
    run(select).map(_.headOption)
  }
}
