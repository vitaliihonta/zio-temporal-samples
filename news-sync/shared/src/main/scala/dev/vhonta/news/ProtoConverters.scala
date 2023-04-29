package dev.vhonta.news

import zio.temporal.protobuf.{EnumProtoType, ProtoType}

trait ProtoConverters {
  implicit val languageProtoType: ProtoType.Of[NewsTopicLanguage, proto.NewsTopicLanguage] =
    EnumProtoType(proto.NewsTopicLanguage).to(NewsTopicLanguage)

  implicit val integrationProtoType: ProtoType.Of[NewsFeedIntegrationType, proto.NewsFeedIntegrationType] =
    EnumProtoType(proto.NewsFeedIntegrationType).to(NewsFeedIntegrationType)
}

object ProtoConverters extends ProtoConverters
