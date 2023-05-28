package dev.vhonta.content.tgbot.api

import io.circe.Codec

import java.util.UUID
import io.circe.generic.extras.semiauto._

case class SubscriberOAuth2State(
  subscriberId: UUID)

object SubscriberOAuth2State {
  implicit val codec: Codec.AsObject[SubscriberOAuth2State] = deriveConfiguredCodec[SubscriberOAuth2State]
}

case class OAuth2CallbackPayload(
  state: String,
  code:  String,
  scope: String)

object OAuth2CallbackPayload {
  implicit val codec: Codec.AsObject[OAuth2CallbackPayload] = deriveConfiguredCodec[OAuth2CallbackPayload]
}
