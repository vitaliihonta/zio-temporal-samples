package dev.vhonta.news.youtube

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
  private val config = {
    (Config.secret("client_id") ++
      Config.secret("client_secret") ++
      Config.listOf("scopes", Config.string)).nested("oauth2_client")
  }

  val make: ZLayer[HttpTransport with JsonFactory, Config.Error, OAuth2Client] =
    ZLayer.fromZIO {
      ZIO.config(config).flatMap { case (clientId, clientSecret, scopes) =>
        ZIO.environmentWith[HttpTransport with JsonFactory] { env =>
          OAuth2Client(
            env.get[HttpTransport],
            env.get[JsonFactory],
            clientId.value.asString,
            clientSecret.value.asString,
            scopes
          )
        }
      }
    }

  case class AccessToken(value: String) {
    override def toString: String = s"AccessToken(***)"
  }
}

case class OAuth2Client(
  httpTransport: HttpTransport,
  jsonFactory:   JsonFactory,
  clientId:      String,
  clientSecret:  String,
  scopes:        List[String]) {

  private val authFlow = new GoogleAuthorizationCodeFlow.Builder(
    httpTransport,
    jsonFactory,
    clientId,
    clientSecret,
    scopes.asJavaCollection
  ).build()

  def getAuthUri(redirectUri: String, state: String): UIO[String] = ZIO.succeed {
    authFlow
      .newAuthorizationUrl()
      .setRedirectUri(redirectUri)
      .setState(state)
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
        clientId,
        clientSecret
      ).execute()
    }
}
