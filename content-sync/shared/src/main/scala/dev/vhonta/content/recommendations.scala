package dev.vhonta.content

import java.time.LocalDate
import java.util.UUID

case class ContentFeedRecommendation(
  id:          UUID,
  owner:       UUID,
  integration: Long,
  forDate:     LocalDate)

object ContentFeedRecommendation {
  case class View(
    integration: ContentFeedIntegration,
    date:        LocalDate,
    items:       List[ContentFeedItem])
}

case class ContentFeedRecommendationItem(
  recommendation: UUID,
  item:           UUID)
