package dev.vhonta.news.puller

import zio.temporal.protobuf.{ProtoType, EnumProtoType}
import dev.vhonta.news.NewsTopicLanguage
import dev.vhonta.{news => domain}

package object workflows {
  implicit val languageProtoType: ProtoType.Of[NewsTopicLanguage, NewsSyncLanguage] =
    EnumProtoType(NewsSyncLanguage).to(NewsTopicLanguage)

  implicit val integrationProtoType: ProtoType.Of[domain.NewsFeedIntegrationType, NewsFeedIntegrationType] =
    EnumProtoType(NewsFeedIntegrationType).to(domain.NewsFeedIntegrationType)
}
