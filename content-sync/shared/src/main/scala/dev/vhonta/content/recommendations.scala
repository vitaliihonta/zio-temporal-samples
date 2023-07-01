package dev.vhonta.content

import java.time.{LocalDate, LocalDateTime}
import java.util.UUID

case class ContentFeedRecommendation(
  id:          UUID,
  owner:       UUID,
  integration: Long,
  forDate:     LocalDate)

object ContentFeedRecommendation {
  case class View(
    id:          UUID,
    integration: ContentFeedIntegration,
    date:        LocalDate,
    items:       List[ContentFeedRecommendationItem])
}

case class ContentFeedRecommendationItem(
  recommendation: UUID,
  topic:          Option[UUID],
  title:          String,
  description:    String,
  url:            String,
  contentType:    ContentType)
