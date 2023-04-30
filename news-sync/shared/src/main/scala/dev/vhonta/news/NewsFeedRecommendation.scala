package dev.vhonta.news

import java.time.LocalDate
import java.util.UUID

case class NewsFeedRecommendation(
  id:      UUID,
  owner:   UUID,
  topic:   UUID,
  forDate: LocalDate)

object NewsFeedRecommendation {
  case class View(
    topicId:  UUID,
    topic:    String,
    date:     LocalDate,
    articles: List[NewsFeedArticle])
}

case class NewsFeedRecommendationArticle(
  recommendation: UUID,
  article:        UUID)
