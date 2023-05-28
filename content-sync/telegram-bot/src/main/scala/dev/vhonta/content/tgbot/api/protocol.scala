package dev.vhonta.content.tgbot.api

import java.util.UUID
import zio.json._

@jsonMemberNames(SnakeCase)
case class SubscriberOAuth2State(
  subscriberId: UUID)

object SubscriberOAuth2State {
  implicit val codec: JsonCodec[SubscriberOAuth2State] = DeriveJsonCodec.gen[SubscriberOAuth2State]
}

case class OAuth2CallbackPayload(
  state: SubscriberOAuth2State,
  code:  String,
  scope: String)
