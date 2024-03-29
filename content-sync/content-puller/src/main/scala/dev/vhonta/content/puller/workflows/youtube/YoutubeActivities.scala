package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.ContentFeedIntegrationDetails
import dev.vhonta.content.puller.proto._
import dev.vhonta.content.repository.ContentFeedIntegrationRepository
import dev.vhonta.content.youtube.{OAuth2Client, YoutubeClient}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._

import java.time.{Instant, LocalDateTime, ZoneOffset}

@activityInterface
trait YoutubeActivities {
  def fetchVideos(params: FetchVideosParams): FetchVideosResult
}

object YoutubeActivitiesImpl {
  case class YoutubeConfig(pollInterval: Duration, refreshTokenThreshold: Duration)

  private val config =
    (Config.duration("poll_interval").withDefault(5.seconds) ++
      Config.duration("refresh_token_threshold").withDefault(5.minutes))
      .nested("youtube.puller")
      .map((YoutubeConfig.apply _).tupled)

  val make: ZLayer[
    YoutubeClient with OAuth2Client with ContentFeedIntegrationRepository with ZActivityRunOptions[Any],
    Config.Error,
    YoutubeActivities
  ] = {
    ZLayer.fromZIO(ZIO.config(config)) >>>
      ZLayer.derive[YoutubeActivitiesImpl]
  }
}

class YoutubeActivitiesImpl(
  youtubeClient:         YoutubeClient,
  oauth2Client:          OAuth2Client,
  integrationRepository: ContentFeedIntegrationRepository,
  config:                YoutubeActivitiesImpl.YoutubeConfig
)(implicit options:      ZActivityRunOptions[Any])
    extends YoutubeActivities {

  override def fetchVideos(params: FetchVideosParams): FetchVideosResult = {
    val activityExecutionContext = ZActivity.executionContext

    def process(state: FetchVideosState): Task[FetchVideosResult] = {
      if (state.subscriptionsLeft.values.isEmpty) {
        ZIO.succeed(state.accumulator)
      } else {
        for {
          tokenInfo <- getOrRefreshTokens(state.currentToken)
          subscription = state.subscriptionsLeft.values.head
          rest         = state.subscriptionsLeft.values.tail
          channelId    = subscription.channelId
          _ <- ZIO.logInfo(s"Pulling channel=$channelId name=${subscription.channelName} (channels left: ${rest.size})")
          videos <- youtubeClient
                      .channelVideos(
                        toOAuth2AccessToken(tokenInfo),
                        channelId,
                        minDate = params.minDate.fromProto[LocalDateTime],
                        maxResults = params.maxResults
                      )
                      .runCollect
          updatedState = state
                           .withCurrentToken(tokenInfo)
                           .withSubscriptionsLeft(YoutubeSubscriptionList(rest))
                           .withAccumulator(
                             state.accumulator.addAllValues(
                               videos.view.map { result =>
                                 YoutubeSearchResult(
                                   videoId = result.getId.getVideoId,
                                   title = result.getSnippet.getTitle,
                                   description = Option(result.getSnippet.getDescription),
                                   publishedAt = {
                                     Instant
                                       .ofEpochMilli(result.getSnippet.getPublishedAt.getValue)
                                       .atOffset(ZoneOffset.UTC)
                                       .toLocalDateTime
                                   }
                                 )
                               }.toList
                             )
                           )
          _      <- activityExecutionContext.heartbeat(updatedState)
          _      <- ZIO.logInfo(s"Sleep for ${config.pollInterval}")
          _      <- ZIO.sleep(config.pollInterval)
          result <- process(updatedState)
        } yield result
      }
    }

    ZActivity.run {
      for {
        _         <- ZIO.logInfo(s"Fetching videos")
        tokenInfo <- getOrRefreshTokens(params.integrationId)
        state <- activityExecutionContext
                   .getHeartbeatDetails[FetchVideosState]
                   .someOrElseZIO {
                     youtubeClient
                       .listSubscriptions(toOAuth2AccessToken(tokenInfo))
                       .runCollect
                       .map { subscriptions =>
                         // Limit the number of subscriptions to reduce quota usage
                         val desiredSubscriptions = subscriptions.view
                           .sortBy(s =>
                             Option(s.getContentDetails.getTotalItemCount.toLong)
                               .getOrElse(0L)
                           )(Ordering[Long].reverse)
                           .take(5)

                         FetchVideosState(
                           currentToken = tokenInfo,
                           subscriptionsLeft = YoutubeSubscriptionList(
                             values = desiredSubscriptions.map { subscription =>
                               YoutubeSubscription(
                                 channelId = subscription.getSnippet.getResourceId.getChannelId,
                                 channelName = subscription.getSnippet.getTitle
                               )
                             }.toList
                           ),
                           accumulator = FetchVideosResult(
                             values = Nil
                           )
                         )
                       }
                       .tap(activityExecutionContext.heartbeat(_))
                   }
        result <- process(state)
      } yield result
    }
  }

  private def getOrRefreshTokens(
    integrationId: Long
  ): Task[YoutubeTokenInfo] =
    integrationRepository
      .findById(integrationId)
      .someOrFail(new Exception(s"Integration by id=$integrationId not found"))
      .flatMap { integration =>
        integration.integration match {
          case youtube: ContentFeedIntegrationDetails.Youtube =>
            getOrRefreshTokens(
              YoutubeTokenInfo(
                integrationId = integrationId,
                accessToken = youtube.accessToken,
                refreshToken = youtube.refreshToken,
                exchangedAt = youtube.exchangedAt.toProto,
                expiresInSeconds = youtube.expiresInSeconds
              )
            )
          case other =>
            ZIO.fail(
              new Exception(s"Integration id=$integrationId must have youtube type, got ${other.`type`} instead")
            )
        }
      }

  private def getOrRefreshTokens(
    tokenInfo: YoutubeTokenInfo
  ): Task[YoutubeTokenInfo] = {
    for {
      now <- ZIO.clockWith(_.localDateTime)
      refreshedInfo <- ZIO
                         .when(isExpired(tokenInfo, now)) {
                           ZIO.logInfo("Refreshing access token") *>
                             oauth2Client
                               .refreshCredentials(tokenInfo.refreshToken)
                               .map { response =>
                                 YoutubeTokenInfo(
                                   integrationId = tokenInfo.integrationId,
                                   accessToken = response.getAccessToken,
                                   refreshToken = Option(response.getRefreshToken).getOrElse(tokenInfo.refreshToken),
                                   exchangedAt = now,
                                   expiresInSeconds = response.getExpiresInSeconds
                                 )
                               }
                               .tap(updateTokens)
                         }
                         .someOrElse(tokenInfo)
    } yield refreshedInfo

  }

  private def updateTokens(tokenInfo: YoutubeTokenInfo): Task[Unit] =
    integrationRepository
      .updateDetails(
        tokenInfo.integrationId,
        ContentFeedIntegrationDetails.Youtube(
          accessToken = tokenInfo.accessToken,
          refreshToken = tokenInfo.refreshToken,
          exchangedAt = tokenInfo.exchangedAt.fromProto[LocalDateTime],
          expiresInSeconds = tokenInfo.expiresInSeconds
        )
      )
      .unit

  private def isExpired(tokenInfo: YoutubeTokenInfo, now: LocalDateTime): Boolean = {
    val expiresAt = tokenInfo.exchangedAt
      .fromProto[LocalDateTime]
      .plusSeconds(tokenInfo.expiresInSeconds - config.refreshTokenThreshold.toSeconds)

    now.isAfter(expiresAt)
  }

  private def toOAuth2AccessToken(tokens: YoutubeTokenInfo): OAuth2Client.AccessToken =
    OAuth2Client.AccessToken(tokens.accessToken)
}
