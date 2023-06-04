package dev.vhonta.content

import zio._
import zio.temporal.protobuf.{EnumProtoType, ProtoType}

trait ProtoConverters {
  implicit val languageProtoType: ProtoType.Of[ContentLanguage, proto.ContentLanguage] =
    EnumProtoType(proto.ContentLanguage).to(ContentLanguage)

  implicit val integrationProtoType: ProtoType.Of[ContentFeedIntegrationType, proto.ContentFeedIntegrationType] =
    EnumProtoType(proto.ContentFeedIntegrationType).to(ContentFeedIntegrationType)

  implicit val contentProtoType: ProtoType.Of[ContentType, proto.ContentType] =
    EnumProtoType(proto.ContentType).to(ContentType)

  // TODO: backport to zio-temporal
  implicit val durationProtoType: ProtoType.Of[Duration, Long] =
    ProtoType.longType.convertTo(Duration.fromNanos)(_.toNanos)
}

object ProtoConverters extends ProtoConverters
