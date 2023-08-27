package dev.vhonta.content

import zio.temporal.protobuf.{EnumProtoType, ProtoType}
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime

trait ProtoConverters {
  implicit val languageProtoType: ProtoType.Of[ContentLanguage, proto.ContentLanguage] =
    EnumProtoType(proto.ContentLanguage).to(ContentLanguage)

  implicit val integrationProtoType: ProtoType.Of[ContentFeedIntegrationType, proto.ContentFeedIntegrationType] =
    EnumProtoType(proto.ContentFeedIntegrationType).to(ContentFeedIntegrationType)

  implicit val contentProtoType: ProtoType.Of[ContentType, proto.ContentType] =
    EnumProtoType(proto.ContentType).to(ContentType)

  implicit val contentFeedIntegrationProtoType: ProtoType.Of[ContentFeedIntegration, proto.ContentFeedIntegration] =
    new ProtoType[ContentFeedIntegration] {
      override final type Repr = proto.ContentFeedIntegration

      override def repr(integration: ContentFeedIntegration): Repr = {
        proto.ContentFeedIntegration(
          id = integration.id,
          subscriber = integration.subscriber,
          integration = integration.integration match {
            case ContentFeedIntegrationDetails.NewsApi(token) =>
              proto.ContentFeedIntegrationNewsApiDetails(token)
            case ContentFeedIntegrationDetails.Youtube(accessToken, refreshToken, exchangedAt, expiresInSeconds) =>
              proto.ContentFeedIntegrationYoutubeDetails(
                accessToken,
                refreshToken,
                exchangedAt.toProto,
                expiresInSeconds
              )
          }
        )
      }

      override def fromRepr(integration: Repr): ContentFeedIntegration =
        ContentFeedIntegration(
          id = integration.id,
          subscriber = integration.subscriber.fromProto,
          integration = integration.integration match {
            case proto.ContentFeedIntegrationDetails.Empty =>
              throw new IllegalArgumentException("Cannot convert empty content integration details")
            case proto.ContentFeedIntegrationNewsApiDetails(token, _) =>
              ContentFeedIntegrationDetails.NewsApi(token)
            case proto.ContentFeedIntegrationYoutubeDetails(
                  accessToken,
                  refreshToken,
                  exchangedAt,
                  expiresInSeconds,
                  _
                ) =>
              ContentFeedIntegrationDetails.Youtube(
                accessToken,
                refreshToken,
                exchangedAt.fromProto[LocalDateTime],
                expiresInSeconds
              )
          }
        )
    }
}

object ProtoConverters extends ProtoConverters
