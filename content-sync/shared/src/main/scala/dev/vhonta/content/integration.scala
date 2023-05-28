package dev.vhonta.content

import java.util.UUID
import enumeratum.{Enum, EnumEntry}
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._

import java.time.LocalDateTime
sealed trait ContentFeedIntegrationType extends EnumEntry.Snakecase
object ContentFeedIntegrationType extends Enum[ContentFeedIntegrationType] {
  case object NewsApi extends ContentFeedIntegrationType
  case object Youtube extends ContentFeedIntegrationType

  override val values = findValues
}

sealed abstract class ContentFeedIntegrationDetails(val `type`: ContentFeedIntegrationType)
    extends Product
    with Serializable

object ContentFeedIntegrationDetails {
  private implicit val config: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withSnakeCaseConstructorNames
      .withDiscriminator("type")

  case class NewsApi(token: String) extends ContentFeedIntegrationDetails(ContentFeedIntegrationType.NewsApi)

  case class Youtube(
    accessToken:      String,
    refreshToken:     String,
    exchangedAt:      LocalDateTime,
    expiresInSeconds: Long)
      extends ContentFeedIntegrationDetails(ContentFeedIntegrationType.Youtube)

  private implicit val newsApiCodec: Codec.AsObject[NewsApi] = deriveConfiguredCodec[NewsApi]
  private implicit val youtubeCodec: Codec.AsObject[Youtube] = deriveConfiguredCodec[Youtube]

  implicit val contentFeedIntegrationDetailsCodec: Codec.AsObject[ContentFeedIntegrationDetails] =
    deriveConfiguredCodec[ContentFeedIntegrationDetails]
}

case class ContentFeedIntegration(
  id:          Long,
  subscriber:  UUID,
  integration: ContentFeedIntegrationDetails)
