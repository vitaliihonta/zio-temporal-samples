package dev.vhonta.content

import java.util.UUID
import enumeratum.{Enum, EnumEntry}
import zio.json._
import java.time.LocalDateTime

sealed trait ContentFeedIntegrationType extends EnumEntry.Snakecase
object ContentFeedIntegrationType extends Enum[ContentFeedIntegrationType] {
  case object NewsApi extends ContentFeedIntegrationType
  case object Youtube extends ContentFeedIntegrationType

  override val values = findValues
}

@jsonDiscriminator("type")
sealed abstract class ContentFeedIntegrationDetails(val `type`: ContentFeedIntegrationType)
    extends Product
    with Serializable

object ContentFeedIntegrationDetails {

  @jsonMemberNames(SnakeCase)
  @jsonHint("news_api")
  case class NewsApi(token: String) extends ContentFeedIntegrationDetails(ContentFeedIntegrationType.NewsApi)

  @jsonMemberNames(SnakeCase)
  @jsonHint("youtube")
  case class Youtube(
    accessToken:      String,
    refreshToken:     String,
    exchangedAt:      LocalDateTime,
    expiresInSeconds: Long)
      extends ContentFeedIntegrationDetails(ContentFeedIntegrationType.Youtube)

  private implicit val newsApiCodec: JsonCodec[NewsApi] = DeriveJsonCodec.gen[NewsApi]
  private implicit val youtubeCodec: JsonCodec[Youtube] = DeriveJsonCodec.gen[Youtube]

  implicit val contentFeedIntegrationDetailsCodec: JsonCodec[ContentFeedIntegrationDetails] =
    DeriveJsonCodec.gen[ContentFeedIntegrationDetails]
}

case class ContentFeedIntegration(
  id:          Long,
  subscriber:  UUID,
  integration: ContentFeedIntegrationDetails)
