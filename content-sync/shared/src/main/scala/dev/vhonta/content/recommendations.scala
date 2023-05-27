package dev.vhonta.content

import java.time.LocalDate
import java.util.UUID

case class ContentFeedRecommendation(
  id:      UUID,
  owner:   UUID,
  topic:   UUID,
  forDate: LocalDate)

object ContentFeedRecommendation {
  case class View(
    topicId: UUID,
    topic:   String,
    date:    LocalDate,
    items:   List[ContentFeedItem])
}

case class ContentFeedRecommendationItem(
  recommendation: UUID,
  item:           UUID)
