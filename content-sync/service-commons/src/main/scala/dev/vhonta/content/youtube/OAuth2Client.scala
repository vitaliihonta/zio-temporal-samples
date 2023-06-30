package dev.vhonta.content.youtube

import com.google.api.client.googleapis.auth.oauth2.{
  GoogleAuthorizationCodeFlow,
  GoogleRefreshTokenRequest,
  GoogleTokenResponse
}
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import zio._

import java.io.IOException
import scala.jdk.CollectionConverters._

object OAuth2Client {
  case class ClientConfig(
    clientId:     Config.Secret,
    clientSecret: Config.Secret,
    scopes:       List[String])

  private val config = {
    (Config.secret("client_id") ++
      Config.secret("client_secret") ++
      Config.listOf("scopes", Config.string))
      .nested("oauth2_client")
      .map((ClientConfig.apply _).tupled)
  }

  val make: ZLayer[HttpTransport with JsonFactory, Config.Error, OAuth2Client] =
    ZLayer.fromZIO(ZIO.config(config)) >>>
      ZLayer.fromFunction(
        OAuth2Client(_: HttpTransport, _: JsonFactory, _: ClientConfig)
      )

  case class AccessToken(value: String) {
    override def toString: String = s"AccessToken(***)"
  }
}

case class OAuth2Client(
  httpTransport: HttpTransport,
  jsonFactory:   JsonFactory,
  config:        OAuth2Client.ClientConfig) {

  private val authFlow = new GoogleAuthorizationCodeFlow.Builder(
    httpTransport,
    jsonFactory,
    config.clientId.value.asString,
    config.clientSecret.value.asString,
    config.scopes.asJavaCollection
  ).build()

  def getAuthUri(redirectUri: String, state: String): UIO[String] = ZIO.succeed {
    authFlow
      .newAuthorizationUrl()
      .setRedirectUri(redirectUri)
      .setState(state)
      .setAccessType("offline")
      .setApprovalPrompt("force")
      .build()
  }

  def getCredentials(redirectUri: String, authorizationCode: String): IO[IOException, GoogleTokenResponse] =
    ZIO.attemptBlockingIO {
      authFlow
        .newTokenRequest(authorizationCode)
        .setRedirectUri(redirectUri)
        .execute()
    }

  def refreshCredentials(refreshToken: String): IO[IOException, GoogleTokenResponse] =
    ZIO.attemptBlockingIO {
      new GoogleRefreshTokenRequest(
        httpTransport,
        jsonFactory,
        refreshToken,
        config.clientId.value.asString,
        config.clientSecret.value.asString
      ).execute()
    }
}
