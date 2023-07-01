package dev.vhonta.content.processor.job

import java.time.Instant

case class ContentFeedItemRow(
  integration: Long,
  topic:       Option[String],
  title:       String,
  description: Option[String],
  url:         String,
  publishedAt: Instant,
  contentType: String)
