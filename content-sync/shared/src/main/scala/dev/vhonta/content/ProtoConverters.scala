package dev.vhonta.content

import zio.temporal.protobuf.{EnumProtoType, ProtoType}

trait ProtoConverters {
  implicit val languageProtoType: ProtoType.Of[ContentLanguage, proto.ContentLanguage] =
    EnumProtoType(proto.ContentLanguage).to(ContentLanguage)

  implicit val integrationProtoType: ProtoType.Of[ContentFeedIntegrationType, proto.ContentFeedIntegrationType] =
    EnumProtoType(proto.ContentFeedIntegrationType).to(ContentFeedIntegrationType)

  implicit val contentProtoType: ProtoType.Of[ContentType, proto.ContentType] =
    EnumProtoType(proto.ContentType).to(ContentType)
}

object ProtoConverters extends ProtoConverters
