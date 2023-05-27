package dev.vhonta.content.youtube

import dev.vhonta.content.ContentFeedIntegrationDetails
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

      redirectUri = "https://webhook.site/dc9492bc-2650-433c-b4a2-8b7b0945f9ff"
      authUri           <- oauthClient.getAuthUri(redirectUri, state)
      _                 <- ZIO.logInfo(s"Auth URI: $authUri")
      authorizationCode <- ZIO.consoleWith(_.readLine("Enter the auth code: "))
      tokenResponse     <- oauthClient.getCredentials(redirectUri, authorizationCode)
      _                 <- ZIO.consoleWith(_.readLine("Press enter to continue"))
      now               <- ZIO.clockWith(_.localDateTime)
      creds = ContentFeedIntegrationDetails.Youtube(
                accessToken = tokenResponse.getAccessToken,
                refreshToken = Option(tokenResponse.getRefreshToken).getOrElse("<empty>"),
                exchangedAt = now,
                expiresInSeconds = tokenResponse.getExpiresInSeconds
              )
      _ <- ZIO.consoleWith(
             _.printLine(
               s"Secret:\n${(creds: ContentFeedIntegrationDetails).asJson.noSpaces}"
             )
           )
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
            "youtube.application_name"    -> "zio-temporal-sample-project"
          )
        )
      )
  }
}
