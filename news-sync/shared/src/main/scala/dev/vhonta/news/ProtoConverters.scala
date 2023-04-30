package dev.vhonta.news

import zio.temporal.protobuf.{EnumProtoType, ProtoType}
import java.time.{LocalDate, LocalTime, ZoneId}

trait ProtoConverters {
  implicit val languageProtoType: ProtoType.Of[NewsTopicLanguage, proto.NewsTopicLanguage] =
    EnumProtoType(proto.NewsTopicLanguage).to(NewsTopicLanguage)

  implicit val integrationProtoType: ProtoType.Of[NewsFeedIntegrationType, proto.NewsFeedIntegrationType] =
    EnumProtoType(proto.NewsFeedIntegrationType).to(NewsFeedIntegrationType)

  // TODO: backport to zio-temporal
  implicit val zoneIdProtoType: ProtoType.Of[ZoneId, String] =
    ProtoType.stringType.convertTo(ZoneId.of)(_.getId)

  implicit val localTimeProtoType: ProtoType.Of[LocalTime, Long] =
    ProtoType.longType.convertTo(LocalTime.ofNanoOfDay)(_.toNanoOfDay)

  implicit val localDateProtoType: ProtoType.Of[LocalDate, Long] =
    ProtoType.localDateTimeType.convertTo(_.toLocalDate)(_.atStartOfDay())
}

object ProtoConverters extends ProtoConverters
