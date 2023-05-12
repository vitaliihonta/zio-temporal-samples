package dev.vhonta.news.newsapi

import java.time.{LocalDateTime, OffsetDateTime}
import enumeratum.values.{StringEnum, StringEnumEntry}
import io.circe.{Codec, Decoder, DecodingFailure, HCursor, Json, JsonObject}
import io.circe.generic.extras.semiauto._

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

sealed trait NewsApiResponse[+A]
object NewsApiResponse {
  case class Error(code: String, message: String) extends NewsApiResponse[Nothing]
  case class Ok[+A](value: A)                     extends NewsApiResponse[A]

  private val errorCodec: Codec.AsObject[Error] = deriveConfiguredCodec[Error]

  implicit def codec[A: Codec.AsObject]: Codec.AsObject[NewsApiResponse[A]] =
    new NewsApiResponseCodec[A](Codec.AsObject[A])

  final class NewsApiResponseCodec[A](okCodec: Codec.AsObject[A]) extends Codec.AsObject[NewsApiResponse[A]] {
    override def encodeObject(a: NewsApiResponse[A]): JsonObject = {
      a match {
        case e: Error  => ("status" -> Json.fromString("error")) +: errorCodec.encodeObject(e)
        case Ok(value) => ("status" -> Json.fromString("ok")) +: okCodec.encodeObject(value)
      }
    }

    override def apply(c: HCursor): Decoder.Result[NewsApiResponse[A]] = {
      c.get[String]("status").flatMap {
        case "error" => errorCodec(c)
        case "ok"    => okCodec(c).map(Ok(_))
        case other   => Left(DecodingFailure(s"Unexpected response status: $other", c.history))
      }
    }
  }
}

case class NewsSource(
  id:   Option[String],
  name: String)

object NewsSource {
  implicit val codec: Codec.AsObject[NewsSource] = deriveConfiguredCodec[NewsSource]
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
  implicit val codec: Codec.AsObject[Article] = deriveConfiguredCodec[Article]
}

case class EverythingResponse(
  totalResults: Int,
  articles:     List[Article])

object EverythingResponse {
  implicit val codec: Codec.AsObject[EverythingResponse] = deriveConfiguredCodec[EverythingResponse]
}
