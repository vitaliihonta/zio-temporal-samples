package dev.vhonta.news.youtube

import io.circe.Json
import io.circe.syntax._
import zio._
import java.util.Base64

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

      redirectUri = ""
      authUri           <- oauthClient.getAuthUri(redirectUri, state)
      _                 <- ZIO.logInfo(s"Auth URI: $authUri")
      authorizationCode <- ZIO.consoleWith(_.readLine("Enter the auth code: "))
      tokenResponse     <- oauthClient.getCredentials(redirectUri, authorizationCode)
      accessToken = tokenResponse.getAccessToken
      _             <- ZIO.logInfo(s"Access token: $authUri")
      _             <- ZIO.consoleWith(_.readLine("Press enter to continue"))
      subscriptions <- youtubeClient.listSubscriptions(accessToken)
      _             <- ZIO.logInfo(s"My subscriptions: $subscriptions")
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
            "oauth2_client.scopes"        -> "",
            "youtube.application-name"    -> "zio-temporal-sample-project"
          )
        )
      )
  }
}
