package dev.vhonta.content.youtube

import com.google.api.client.auth.oauth2.{BearerToken, Credential}
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.{SearchListResponse, SubscriptionListResponse}
import zio._

import java.io.IOException
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneId, ZoneOffset}
import java.{util => ju}

object YoutubeClient {
  private val config = Config.string("application-name").nested("youtube")

  private val youtubeLayer: ZLayer[HttpTransport with JsonFactory, Config.Error, YouTube] = {
    ZLayer.fromZIO {
      ZIO.config(config).flatMap { appName =>
        ZIO.environmentWith[HttpTransport with JsonFactory] { env =>
          val creds = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
            .setTransport(env.get[HttpTransport])
            .setJsonFactory(env.get[JsonFactory])
            .build()

          new YouTube.Builder(env.get[HttpTransport], env.get[JsonFactory], creds)
            .setApplicationName(appName)
            .build()
        }
      }
    }
  }

  val make: ZLayer[HttpTransport with JsonFactory, Config.Error, YoutubeClient] =
    ZLayer.makeSome[HttpTransport with JsonFactory, YoutubeClient](
      ZLayer.fromFunction(YoutubeClient(_: YouTube)),
      youtubeLayer
    )
}

case class YoutubeClient(youtube: YouTube) {

  def listSubscriptions(accessToken: OAuth2Client.AccessToken): IO[IOException, SubscriptionListResponse] =
    ZIO.attemptBlockingIO {
      youtube
        .subscriptions()
        .list(ju.List.of("id", "snippet", "contentDetails"))
        .setMine(true)
        .setAccessToken(accessToken.value)
        .execute()
    }

  def channelVideos(
    accessToken: OAuth2Client.AccessToken,
    channelId:   String,
    minDate:     LocalDateTime,
    maxResults:  Long,
    pageToken:   Option[String]
  ): IO[IOException, SearchListResponse] = {
    ZIO.attemptBlockingIO {
      youtube
        .search()
        .list(ju.List.of("id", "snippet"))
        .setChannelId(channelId)
        .setPageToken(pageToken.orNull)
        .setOrder("date")
        .setPublishedAfter(minDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))
        .setType(ju.List.of("video"))
        .setAccessToken(accessToken.value)
        .setMaxResults(maxResults)
        .execute()
    }
  }
}
