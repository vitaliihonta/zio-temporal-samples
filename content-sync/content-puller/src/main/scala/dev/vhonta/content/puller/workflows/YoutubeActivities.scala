package dev.vhonta.content.puller.workflows

import com.google.api.services.youtube.model.{Subscription, SubscriptionListResponse}
import dev.vhonta.content.youtube.{OAuth2Client, YoutubeClient}
import dev.vhonta.content.puller.proto
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

import java.time.LocalDateTime
import scala.jdk.CollectionConverters._

@activityInterface
trait YoutubeActivities {
  def fetchVideos(parameters: Any): Any
}

case class YoutubeTokenInfo(
  accessToken:      String,
  refreshToken:     String,
  exchangedAt:      LocalDateTime,
  expiresInSeconds: Long) {

  def isExpired(now: LocalDateTime): Boolean = {
    val expiresAt = exchangedAt.plusSeconds(expiresInSeconds + YoutubeTokenInfo.ThresholdSeconds)
    now.isAfter(expiresAt)
  }

  def toOAuth2AccessToken: OAuth2Client.AccessToken =
    OAuth2Client.AccessToken(accessToken)
}

object YoutubeTokenInfo {
  val ThresholdSeconds: Long = 300
}

object YoutubeActivitiesImpl {}

case class YoutubeActivitiesImpl(
  youtubeClient:    YoutubeClient,
  oauth2Client:     OAuth2Client,
  pollInterval:     Duration
)(implicit options: ZActivityOptions[Any])
    extends YoutubeActivities {

  // TODO: decide on output signature
  override def fetchVideos(parameters: Any): Any = {
    val activityExecutionContext = ZActivity.executionContext

    // TODO: make activity parameters
    val currentToken: YoutubeTokenInfo = ???
    val minDate: LocalDateTime         = ???
    val maxResults: Long               = ???

    def process(currentToken: YoutubeTokenInfo, subscriptions: List[Subscription]): Task[Any] =
      subscriptions match {
        case Nil => ZIO.unit
        case subscription :: rest =>
          for {
            tokenInfo <- getOrRefreshTokens(currentToken)
            channelId = subscription.getSnippet.getResourceId.getChannelId
            _ <- ZIO.logInfo(s"Pulling channel=$channelId (channels left: ${rest.size})")
            // TODO: handle searchResponse
            searchResponse <- youtubeClient.channelVideos(
                                currentToken.toOAuth2AccessToken,
                                channelId,
                                minDate = minDate,
                                maxResults = maxResults
                              )
            _      <- activityExecutionContext.heartbeat(rest)
            _      <- ZIO.logInfo(s"Sleep for $pollInterval")
            _      <- ZIO.sleep(pollInterval)
            result <- process(tokenInfo, rest)
          } yield result
      }

    ZActivity.run {
      for {
        _         <- ZIO.logInfo(s"Fetching videos")
        tokenInfo <- getOrRefreshTokens(currentToken)
        subscriptions <- activityExecutionContext
                           .getHeartbeatDetails[List[Subscription]]
                           .someOrElseZIO {
                             youtubeClient
                               .listSubscriptions(tokenInfo.toOAuth2AccessToken)
                               .map(_.getItems.asScala.toList)
                               .tap(activityExecutionContext.heartbeat(_))
                           }
        result <- process(tokenInfo, subscriptions)
      } yield result
    }
  }

  private def getOrRefreshTokens(
    tokenInfo: YoutubeTokenInfo
  ): Task[YoutubeTokenInfo] = {
    for {
      now <- ZIO.clockWith(_.localDateTime)
      refreshedInfo <- ZIO
                         .when(tokenInfo.isExpired(now)) {
                           ZIO.logInfo("Refreshing access token") *>
                             oauth2Client
                               .refreshCredentials(tokenInfo.refreshToken)
                               .map { response =>
                                 YoutubeTokenInfo(
                                   accessToken = response.getAccessToken,
                                   refreshToken = response.getRefreshToken,
                                   exchangedAt = now,
                                   expiresInSeconds = response.getExpiresInSeconds
                                 )
                               }
                         }
                         .someOrElse(tokenInfo)
    } yield refreshedInfo

  }
}
