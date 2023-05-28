package dev.vhonta.content.tgbot.api

import dev.vhonta.content.tgbot.proto.YoutubeCallbackData
import dev.vhonta.content.tgbot.workflow.SetupYoutubeWorkflow
import zio._
import zio.http._
import zio.json._
import zio.temporal.workflow._

import java.util.Base64
import scala.util.control.NoStackTrace

object YoutubeCallbackHandlingApi {
  case class ApiConfig(botUsername: String)

  private val config = Config
    .string("username")
    .map(ApiConfig(_))
    .nested("telegram", "bot")

  val make: ZLayer[ZWorkflowClient, Config.Error, YoutubeCallbackHandlingApi] = {
    ZLayer.fromZIO(ZIO.config(config)) >>>
      ZLayer.fromFunction(YoutubeCallbackHandlingApi(_: ApiConfig, _: ZWorkflowClient))
  }

  case class InvalidCallbackPayloadException(message: String) extends Exception(message) with NoStackTrace
}

case class YoutubeCallbackHandlingApi(config: YoutubeCallbackHandlingApi.ApiConfig, workflowClient: ZWorkflowClient) {
  import YoutubeCallbackHandlingApi.InvalidCallbackPayloadException

  private val decoder = Base64.getDecoder

  val httpApp: HttpApp[Any, Nothing] = {
    Http.collectHttp[Request] {
      case Method.GET -> !! / "oauth2" =>
        callbackDataHandler
          .catchAllZIO { error =>
            val statusCode = error match {
              case _: InvalidCallbackPayloadException =>
                Status.BadRequest
              case _ => Status.InternalServerError
            }
            ZIO
              .logError(s"OAuth2 callback handler failed $error")
              .as(Response.status(statusCode))
          }
      case Method.GET -> !! / "health" =>
        Http.fromHandler(Handler.ok)
    }
  }

  private val callbackDataHandler: Http[Any, Exception, Request, Response] = Http.fromOptionalHandler[Request] { req =>
    val callbackData = for {
      rawState <- getSingleQueryParam(req.url)("state")
      code     <- getSingleQueryParam(req.url)("code")
      scope    <- getSingleQueryParam(req.url)("scope")
    } yield {
      (rawState, code, scope)
    }
    callbackData.map { case (rawState, code, scope) =>
      Handler.fromZIO {
        for {
          stateDecoded <- ZIO.attempt(new String(decoder.decode(rawState))).refineToOrDie[IllegalArgumentException]
          state <- ZIO
                     .fromEither(stateDecoded.fromJson[SubscriberOAuth2State])
                     .mapError(InvalidCallbackPayloadException)
          _ <- ZIO.logInfo(s"Received youtube callback from subscriber=${state.subscriberId} scope=$scope")
          youtubeWorkflow <- workflowClient.newWorkflowStub[SetupYoutubeWorkflow](
                               workflowId = SetupYoutubeWorkflow.workflowId(state.subscriberId)
                             )
          _ <- ZIO.logInfo("Sending callback data...")
          _ <- ZWorkflowStub.signal(
                 youtubeWorkflow.provideCallbackData(
                   YoutubeCallbackData(
                     authorizationCode = code
                   )
                 )
               )
          url <- ZIO.fromEither(
                   URL.decode(s"https://t.me/${config.botUsername}?start=")
                 )
        } yield Response.redirect(url)
      }
    }
  }

  private def getSingleQueryParam(url: URL)(name: String): Option[String] =
    url.queryParams.get(name).flatMap(_.headOption)
}
