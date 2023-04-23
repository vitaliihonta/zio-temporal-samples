package dev.vhonta.news.puller

import zio.temporal.protobuf.{ProtoType, EnumProtoType}
import dev.vhonta.news.NewsTopicLanguage
package object workflows {
  implicit val languageProtoType: ProtoType.Of[NewsTopicLanguage, NewsSyncLanguage] =
    EnumProtoType(NewsSyncLanguage).to(NewsTopicLanguage)
}
