package dev.vhonta.content

import zio.temporal.protobuf.{EnumProtoType, ProtoType}

trait ProtoConverters {
  implicit val languageProtoType: ProtoType.Of[ContentLanguage, proto.ContentLanguage] =
    EnumProtoType(proto.ContentLanguage).to(ContentLanguage)

  implicit val integrationProtoType: ProtoType.Of[ContentFeedIntegrationType, proto.ContentFeedIntegrationType] =
    EnumProtoType(proto.ContentFeedIntegrationType).to(ContentFeedIntegrationType)
}

object ProtoConverters extends ProtoConverters
