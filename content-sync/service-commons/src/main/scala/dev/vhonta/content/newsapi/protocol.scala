package dev.vhonta.content.newsapi

import java.time.{LocalDateTime, OffsetDateTime}
import enumeratum.values.{StringEnum, StringEnumEntry}
import zio.json._

sealed abstract class SortBy(override val value: String) extends StringEnumEntry
object SortBy extends StringEnum[SortBy] {
  case object Relevancy   extends SortBy("relevancy")
  case object Popularity  extends SortBy("popularity")
  case object PublishedAt extends SortBy("publishedAt")

  override val values = findValues
}

case class EverythingRequest(
  query:    String,
  language: String,
  from:     Option[LocalDateTime],
  to:       Option[LocalDateTime],
  sortBy:   SortBy,
  pageSize: Int,
  page:     Int)

@jsonDiscriminator("status")
sealed trait NewsApiResponse[+A]
object NewsApiResponse {
  @jsonHint("error")
  case class Error(code: String, message: String) extends NewsApiResponse[Nothing]

  @jsonHint("ok")
  case class Ok[+A](value: A) extends NewsApiResponse[A]

  private implicit val errorCodec: JsonCodec[Error] = DeriveJsonCodec.gen[Error]
  private implicit def okCodec[A: JsonCodec]: JsonCodec[Ok[A]] =
    JsonCodec[A].transform(Ok(_), _.value)

  implicit def codec[A: JsonCodec]: JsonCodec[NewsApiResponse[A]] =
    DeriveJsonCodec.gen[NewsApiResponse[A]]
}

case class NewsSource(
  id:   Option[String],
  name: String)

object NewsSource {
  implicit val codec: JsonCodec[NewsSource] = DeriveJsonCodec.gen[NewsSource]
}

case class Article(
  source:      NewsSource,
  author:      Option[String],
  title:       Option[String],
  description: Option[String],
  url:         String,
  publishedAt: OffsetDateTime,
  content:     Option[String])

object Article {
  implicit val codec: JsonCodec[Article] = DeriveJsonCodec.gen[Article]
}

case class EverythingResponse(
  totalResults: Int,
  articles:     List[Article])

object EverythingResponse {
  implicit val codec: JsonCodec[EverythingResponse] = DeriveJsonCodec.gen[EverythingResponse]
}
