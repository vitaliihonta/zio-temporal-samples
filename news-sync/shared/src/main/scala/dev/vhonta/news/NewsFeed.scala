package dev.vhonta.news

import enumeratum.{Enum, EnumEntry}

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID

sealed abstract class NewsTopicLanguage(val code: String) extends EnumEntry
object NewsTopicLanguage extends Enum[NewsTopicLanguage] {
  case object English   extends NewsTopicLanguage("en")
  case object French    extends NewsTopicLanguage("fr")
  case object Spanish   extends NewsTopicLanguage("es")
  case object Ukrainian extends NewsTopicLanguage("uk")

  override val values = findValues
}

case class NewsFeedTopic(
  id:    UUID,
  owner: UUID, /*reader ID*/
  topic: String,
  lang:  NewsTopicLanguage)

case class NewsFeedArticle(
  id:          UUID,
  topic:       UUID,
  title:       String,
  description: String,
  url:         String,
  publishedAt: LocalDateTime)
