package dev.vhonta.content

import dev.vhonta.content.proto
import dev.vhonta.content.proto.{NewsApiIntegrationState, YoutubePullerIntegrationState}
import io.getquill.JsonbValue
import zio.json._
import zio.temporal.protobuf.ProtoType

import java.time.LocalDateTime

@jsonDiscriminator("type")
sealed trait PullerStateValue {
  def lastProcessedAt: LocalDateTime
}
object PullerStateValue {
  @jsonHint("news_api")
  @jsonMemberNames(SnakeCase)
  case class NewsApi(lastProcessedAt: LocalDateTime) extends PullerStateValue

  @jsonHint("youtube")
  @jsonMemberNames(SnakeCase)
  case class Youtube(lastProcessedAt: LocalDateTime) extends PullerStateValue

  private implicit val newsApiCodec: JsonCodec[NewsApi] = DeriveJsonCodec.gen[NewsApi]
  private implicit val youtubeCodec: JsonCodec[Youtube] = DeriveJsonCodec.gen[Youtube]

  implicit val pullerStateValueJsonCodec: JsonCodec[PullerStateValue] =
    DeriveJsonCodec.gen[PullerStateValue]
}

case class PullerState(
  integration: Long,
  value:       JsonbValue[PullerStateValue]) {}

object PullerState {

  import zio.temporal.protobuf.syntax._

  implicit val protoType: ProtoType.Of[PullerState, proto.PullerState] =
    new ProtoType[PullerState] {
      override type Repr = proto.PullerState

      override def repr(state: PullerState): proto.PullerState = {
        state.value.value match {
          case newsApi: PullerStateValue.NewsApi =>
            NewsApiIntegrationState(state.integration, newsApi.lastProcessedAt)
          case youtube: PullerStateValue.Youtube =>
            YoutubePullerIntegrationState(state.integration, youtube.lastProcessedAt)
        }
      }

      override def fromRepr(state: proto.PullerState): PullerState = {
        state match {
          case proto.PullerState.Empty =>
            throw new IllegalArgumentException("Cannot convert empty puller state")

          case newsApi: NewsApiIntegrationState =>
            PullerState(
              newsApi.integrationId,
              JsonbValue(
                PullerStateValue.NewsApi(newsApi.lastProcessedAt.fromProto[LocalDateTime])
              )
            )
          case youtube: YoutubePullerIntegrationState =>
            PullerState(
              youtube.integrationId,
              JsonbValue(
                PullerStateValue.Youtube(youtube.lastProcessedAt.fromProto[LocalDateTime])
              )
            )
        }
      }
    }

  def safeFromProto(state: proto.PullerState): Option[PullerState] =
    state match {
      case proto.PullerState.Empty => None
      case _                       => Some(state.fromProto)
    }
}
