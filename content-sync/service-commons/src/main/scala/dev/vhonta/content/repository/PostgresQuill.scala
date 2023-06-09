package dev.vhonta.content.repository

import dev.vhonta.content.ContentFeedIntegrationDetails
import enumeratum.{Enum, EnumEntry}
import io.getquill.{JsonValue, NamingStrategy, SnakeCase}
import io.getquill.jdbczio.Quill
import zio._

import java.time.ZoneId
import javax.sql.DataSource

class PostgresQuill[+N <: NamingStrategy](override val naming: N, override val ds: DataSource)
    extends Quill.Postgres[N](naming, ds) {

  implicit def stringEnumEncoder[E <: EnumEntry]: MappedEncoding[E, String] =
    MappedEncoding(_.entryName)

  implicit def stringEnumDecoder[E <: EnumEntry](implicit e: Enum[E]): MappedEncoding[String, E] =
    MappedEncoding(e.withNameInsensitive)

  implicit val zoneIdEncoder: MappedEncoding[ZoneId, String] =
    MappedEncoding(_.getId)

  implicit val zoneIdDecoder: MappedEncoding[String, ZoneId] =
    MappedEncoding(ZoneId.of)

  implicit val integrationDetailsDecoder
    : MappedEncoding[JsonValue[ContentFeedIntegrationDetails], ContentFeedIntegrationDetails] =
    MappedEncoding(_.value)

  implicit val integrationDetailsEncoder
    : MappedEncoding[ContentFeedIntegrationDetails, JsonValue[ContentFeedIntegrationDetails]] =
    MappedEncoding(JsonValue(_))
}

object PostgresQuill {
  val make: URLayer[javax.sql.DataSource, PostgresQuill[SnakeCase]] =
    ZLayer.fromFunction((ds: javax.sql.DataSource) => new PostgresQuill[SnakeCase](SnakeCase, ds))
}
