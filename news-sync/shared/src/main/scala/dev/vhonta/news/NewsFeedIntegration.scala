package dev.vhonta.news

import java.util.UUID
import enumeratum.{Enum, EnumEntry}
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
sealed trait NewsFeedIntegrationType extends EnumEntry.Snakecase
object NewsFeedIntegrationType extends Enum[NewsFeedIntegrationType] {
  case object NewsApi extends NewsFeedIntegrationType
  override val values = findValues
}

sealed abstract class NewsFeedIntegrationDetails(val `type`: NewsFeedIntegrationType) extends Product with Serializable
object NewsFeedIntegrationDetails {
  private implicit val config: Configuration =
    Configuration.default.withSnakeCaseMemberNames.withSnakeCaseConstructorNames
      .withDiscriminator("type")
  // TODO: may add reddit later
  case class NewsApi(token: String) extends NewsFeedIntegrationDetails(NewsFeedIntegrationType.NewsApi)

  private implicit val newsApiCodec: Codec[NewsApi] = deriveConfiguredCodec[NewsApi]
  implicit val newsFeedIntegrationDetailsCodec: Codec[NewsFeedIntegrationDetails] =
    deriveConfiguredCodec[NewsFeedIntegrationDetails]
}

case class NewsFeedIntegration(
  id:          Long,
  reader:      UUID,
  integration: NewsFeedIntegrationDetails)
