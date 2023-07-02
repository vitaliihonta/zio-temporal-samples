package dev.vhonta.content.processor

import java.time.{Instant, LocalDate}
import zio.json._

case class ContentFeedItemRow(
  integrationType: String,
  pulledAt:        Long,
  integration:     Long,
  topic:           Option[String],
  title:           String,
  description:     Option[String],
  url:             String,
  publishedAt:     Instant,
  contentType:     String)

case class ContentFeedRecommendationItemRow(
  integration: Long,
  topic:       Option[String],
  title:       String,
  description: String,
  url:         String,
  forDate:     LocalDate,
  contentType: String)

case class ProcessingResult(
  integration: Long,
  date:        LocalDate,
  inserted:    Long)

object ProcessingResult {
  implicit val processingResultCodec: JsonCodec[ProcessingResult] =
    DeriveJsonCodec.gen[ProcessingResult]
}
