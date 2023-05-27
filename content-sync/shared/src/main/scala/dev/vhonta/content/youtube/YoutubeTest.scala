package dev.vhonta.content.youtube

import io.circe.Json
import io.circe.syntax._
import zio._
import java.time.LocalDateTime
import java.util.Base64
import scala.jdk.CollectionConverters._

// TODO: remove
object YoutubeTest extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val program = for {
      oauthClient   <- ZIO.service[OAuth2Client]
      youtubeClient <- ZIO.service[YoutubeClient]
      state = Base64.getEncoder.encodeToString(
                Json
                  .obj(
                    "userId" -> "VHONTA".asJson,
                    "ts"     -> 123.asJson
                  )
                  .noSpaces
                  .getBytes
              )

      redirectUri = "https://webhook.site/b5765be8-e94b-48d3-a77f-6768b5c9223f"
      authUri           <- oauthClient.getAuthUri(redirectUri, state)
      _                 <- ZIO.logInfo(s"Auth URI: $authUri")
      authorizationCode <- ZIO.consoleWith(_.readLine("Enter the auth code: "))
      tokenResponse     <- oauthClient.getCredentials(redirectUri, authorizationCode)
      _                 <- ZIO.consoleWith(_.readLine("Press enter to continue"))
      accessToken = OAuth2Client.AccessToken(tokenResponse.getAccessToken)
      _             <- ZIO.logInfo(s"Access token: ${accessToken.value}")
      subscriptions <- youtubeClient.listSubscriptions(accessToken)
      _             <- ZIO.logInfo(s"My subscriptions: $subscriptions")
      subs      = subscriptions.getItems.asScala.head
      channelId = subs.getSnippet.getResourceId.getChannelId
      _ <- ZIO.logInfo(s"Search last videos channelId=$channelId ${subs.getSnippet.getTitle}...")
      videos <- youtubeClient.channelVideos(
                  accessToken,
                  channelId,
                  minDate = LocalDateTime.of(2023, 5, 5, 0, 0),
                  maxResults = 10
                )
      _ <- ZIO.logInfo(s"Found videos: $videos")
    } yield ()

    program
      .provideSome[Scope](
        OAuth2Client.make,
        YoutubeClient.make,
        GoogleModule.make
      )
      .withConfigProvider(
        ConfigProvider.fromMap(
          Map(
            "oauth2_client.client_id"     -> "",
            "oauth2_client.client_secret" -> "",
            "oauth2_client.scopes[0]"     -> "https://www.googleapis.com/auth/youtube.readonly",
            "youtube.application-name"    -> "zio-temporal-sample-project"
          )
        )
      )
  }
}
