package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.{ContentFeedIntegration, ContentFeedIntegrationDetails}
import dev.vhonta.content.proto
import dev.vhonta.content.repository.ContentFeedIntegrationRepository
import dev.vhonta.content.tgbot.api.SubscriberOAuth2State
import dev.vhonta.content.tgbot.proto._
import dev.vhonta.content.youtube.{OAuth2Client, YoutubeClient}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import io.circe.syntax._
import java.time.LocalDateTime

@activityInterface
trait YoutubeActivities {
  @activityMethod(name = "YoutubeInitializeOAuth2")
  def initializeOAuth2(params: InitializeOAuth2Params): InitializeOAuth2Result

  @activityMethod(name = "YoutubeGetOAuth2Credentials")
  def getOAuth2Credentials(params: GetOAuth2CredentialsParams): GetOAuth2CredentialsResult

  @activityMethod(name = "YoutubeTestAuth2Credentials")
  def testOAuth2Credentials(params: TestOAuth2CredentialsParams): TestOAuth2CredentialsResult

  def storeYoutubeIntegration(params: StoreYoutubeIntegrationParams): StoreNewsApiIntegrationResult
}

case class YoutubeActivitiesImpl(
  oauth2Client:          OAuth2Client,
  youtubeClient:         YoutubeClient,
  integrationRepository: ContentFeedIntegrationRepository
)(implicit options:      ZActivityOptions[Any])
    extends YoutubeActivities {
  override def initializeOAuth2(params: InitializeOAuth2Params): InitializeOAuth2Result = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Initializing OAuth2 flow for subscriber=${params.subscriber.fromProto}")
        authorizationUri <- oauth2Client.getAuthUri(
                              redirectUri = params.redirectUri,
                              state = SubscriberOAuth2State(
                                subscriberId = params.subscriber.fromProto
                              ).asJson.noSpaces
                            )
      } yield InitializeOAuth2Result(authorizationUri)
    }
  }

  override def getOAuth2Credentials(params: GetOAuth2CredentialsParams): GetOAuth2CredentialsResult = {
    ZActivity.run {
      for {
        _             <- ZIO.logInfo(s"Getting OAuth2 credentials")
        now           <- ZIO.clockWith(_.localDateTime)
        tokenResponse <- oauth2Client.getCredentials(params.redirectUri, params.authorizationCode)
      } yield GetOAuth2CredentialsResult(
        creds = proto.ContentFeedIntegrationYoutubeDetails(
          accessToken = tokenResponse.getAccessToken,
          refreshToken = tokenResponse.getRefreshToken,
          exchangedAt = now,
          expiresInSeconds = tokenResponse.getExpiresInSeconds
        )
      )
    }
  }

  override def testOAuth2Credentials(params: TestOAuth2CredentialsParams): TestOAuth2CredentialsResult = {
    ZActivity.run {
      val test = for {
        _ <- ZIO.logInfo("Testing OAuth2 credentials...")
        accessToken = OAuth2Client.AccessToken(params.creds.accessToken)
        subs <- youtubeClient
                  .listSubscriptions(accessToken)
                  .take(5)
                  .runCollect
        _ <- ZIO.logInfo(
               s"Found ${subs.size} subscriptions: ${subs.map(_.getSnippet.getChannelTitle).mkString("[", ", ", "]")}"
             )
      } yield ()

      test.fold(
        failure => TestOAuth2CredentialsResult(error = Some(failure.getMessage)),
        _ => TestOAuth2CredentialsResult(error = None)
      )
    }
  }

  override def storeYoutubeIntegration(params: StoreYoutubeIntegrationParams): StoreNewsApiIntegrationResult = {
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Creating a youtube integration subscriber=${params.subscriber.fromProto}")
        integration <- integrationRepository.create(
                         ContentFeedIntegration(
                           id = -1,
                           subscriber = params.subscriber.fromProto,
                           integration = ContentFeedIntegrationDetails.Youtube(
                             accessToken = params.creds.accessToken,
                             refreshToken = params.creds.refreshToken,
                             exchangedAt = params.creds.exchangedAt.fromProto[LocalDateTime],
                             expiresInSeconds = params.creds.expiresInSeconds
                           )
                         )
                       )
      } yield StoreNewsApiIntegrationResult(
        integration = proto.ContentFeedIntegration(
          id = integration.id,
          subscriber = integration.subscriber,
          integration = proto.ContentFeedIntegration.Integration.Youtube(
            proto.ContentFeedIntegrationYoutubeDetails(
              accessToken = params.creds.accessToken,
              refreshToken = params.creds.refreshToken,
              exchangedAt = params.creds.exchangedAt.toProto,
              expiresInSeconds = params.creds.expiresInSeconds
            )
          )
        )
      )
    }
  }
}
