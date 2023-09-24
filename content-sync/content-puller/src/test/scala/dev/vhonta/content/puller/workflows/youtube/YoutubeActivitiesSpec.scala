package dev.vhonta.content.puller.workflows.youtube

import com.google.api.services.youtube.model.{
  ResourceId,
  SearchResult,
  SearchResultSnippet,
  Subscription,
  SubscriptionContentDetails,
  SubscriptionSnippet
}
import dev.vhonta.content.{ContentFeedIntegration, ContentFeedIntegrationDetails}
import dev.vhonta.content.puller.proto.{FetchVideosParams, FetchVideosState}
import dev.vhonta.content.repository.ContentFeedIntegrationRepository
import dev.vhonta.content.youtube.{OAuth2Client, YoutubeClient}
import zio._
import zio.logging.backend.SLF4J
import zio.test._
import zio.temporal.testkit._
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import zio.stream.ZStream
import zio.temporal.activity.ZActivityOptions

import java.util.UUID

object YoutubeActivitiesSpec extends ZIOSpecDefault with IdiomaticMockito with ArgumentMatchersSugar {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override val spec = suite("YoutubeActivities")(
    test("does nothing if no subscriptions found") {
      val youtubeClient         = mock[YoutubeClient]
      val oauth2Client          = mock[OAuth2Client]
      val integrationRepository = mock[ContentFeedIntegrationRepository]
      val config = YoutubeActivitiesImpl.YoutubeConfig(pollInterval = 1.second, refreshTokenThreshold = 2.hours)

      ZTestActivityEnvironment.activityRunOptionsWithZIO[Any] { implicit options =>
        for {
          _ <- ZTestActivityEnvironment.addActivityImplementation(
                 YoutubeActivitiesImpl(youtubeClient, oauth2Client, integrationRepository, config)
               )

          stub <- ZTestActivityEnvironment.newActivityStub[YoutubeActivities](
                    ZActivityOptions.withStartToCloseTimeout(10.seconds)
                  )

          integrationId = 1
          subscriber  <- ZIO.randomWith(_.nextUUID)
          integration <- makeYoutubeIntegration(integrationId, subscriber)
          _ = {
            integrationRepository
              .findById(*)
              .returns(
                ZIO.some(integration)
              )

            youtubeClient.listSubscriptions(*).returns(ZStream.empty)
          }

          result = stub.fetchVideos(
                     FetchVideosParams(
                       integrationId = integrationId,
                       minDate = 0,
                       maxResults = 100
                     )
                   )
        } yield {
          assertTrue(result.values.isEmpty)
        }
      }
    },
    test("fetches videos for top 5 subscriptions") {
      val youtubeClient         = mock[YoutubeClient]
      val oauth2Client          = mock[OAuth2Client]
      val integrationRepository = mock[ContentFeedIntegrationRepository]
      val config = YoutubeActivitiesImpl.YoutubeConfig(pollInterval = 1.second, refreshTokenThreshold = 2.hours)

      ZTestActivityEnvironment.activityRunOptionsWithZIO[Any] { implicit options =>
        for {
          _ <- ZTestActivityEnvironment.addActivityImplementation(
                 YoutubeActivitiesImpl(youtubeClient, oauth2Client, integrationRepository, config)
               )

          stub <- ZTestActivityEnvironment.newActivityStub[YoutubeActivities](
                    ZActivityOptions.withStartToCloseTimeout(10.seconds)
                  )

          integrationId = 1
          subscriber  <- ZIO.randomWith(_.nextUUID)
          integration <- makeYoutubeIntegration(integrationId, subscriber)
          subscriptionsCount = 5
          videosPerSubs      = 5
          _ = {
            integrationRepository
              .findById(*)
              .returns(
                ZIO.some(integration)
              )

            youtubeClient
              .listSubscriptions(*)
              .returns(
                ZStream.fromIterableZIO(
                  ZIO.collectAll(List.fill(subscriptionsCount)(makeSubscription()))
                )
              )

            youtubeClient
              .channelVideos(*, *, *, *)
              .returns(
                ZStream.fromIterableZIO(
                  ZIO.collectAll(List.fill(videosPerSubs)(makeSearchResult()))
                )
              )
          }
          result = stub.fetchVideos(
                     FetchVideosParams(
                       integrationId = integrationId,
                       minDate = 0,
                       maxResults = 100
                     )
                   )
        } yield {
          assertTrue(result.values.size == subscriptionsCount * videosPerSubs)
        }
      }
    }
  ).provide(
    TestModule.activityTestEnv
  ) @@ TestAspect.withLiveClock

  private def makeYoutubeIntegration(integrationId: Long, subscriber: UUID) = {
    ZIO.clockWith(_.localDateTime).map { now =>
      ContentFeedIntegration(
        integrationId,
        subscriber,
        ContentFeedIntegrationDetails.Youtube(
          accessToken = "accessToken",
          refreshToken = "refreshToken",
          exchangedAt = now,
          expiresInSeconds = 36000
        )
      )
    }
  }

  private def makeSubscription(): UIO[Subscription] =
    for {
      channelId <- ZIO.randomWith(_.nextUUID)
      title     <- ZIO.randomWith(_.nextString(10))
    } yield {
      new Subscription()
        .setSnippet(
          new SubscriptionSnippet()
            .setResourceId(
              new ResourceId().setChannelId(channelId.toString)
            )
            .setTitle(title)
        )
        .setContentDetails(
          new SubscriptionContentDetails()
            .setTotalItemCount(1000L)
        )
    }

  private def makeSearchResult(): UIO[SearchResult] =
    for {
      videoId     <- ZIO.randomWith(_.nextUUID)
      title       <- ZIO.randomWith(_.nextString(10))
      publishedAt <- ZIO.clockWith(_.instant)
    } yield {
      new SearchResult()
        .setId(
          new ResourceId().setVideoId(videoId.toString)
        )
        .setSnippet(
          new SearchResultSnippet()
            .setTitle(title)
            .setDescription("sample description")
            .setPublishedAt(new com.google.api.client.util.DateTime(publishedAt.toEpochMilli))
        )
    }
}
