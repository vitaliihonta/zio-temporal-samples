package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.puller.proto.{
  FetchVideosParams,
  PullingResult,
  StoreVideosParameters,
  YoutubePullerParameters,
  YoutubeVideosList
}
import dev.vhonta.content.puller.workflows.DatabaseActivities
import dev.vhonta.content.puller.workflows.base.BasePullWorkflow
import zio.temporal._
import zio._
import zio.temporal.workflow._
import zio.temporal.activity._

@workflowInterface
trait YoutubePullWorkflow extends BasePullWorkflow[YoutubePullerParameters]

class YoutubePullWorkflowImpl extends YoutubePullWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val youtubeActivities = ZWorkflow
    .newActivityStub[YoutubeActivities]
    // it may take long time to process...
    .withStartToCloseTimeout(30.minutes) // TODO: make configurable
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        // bigger coefficient due for rate limiting
        .withBackoffCoefficient(3)
    )
    .build

  private val databaseActivities = ZWorkflow
    .newActivityStub[DatabaseActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(5)
    )
    .build

  override def pull(params: YoutubePullerParameters): PullingResult = {
    logger.info(
      s"Getting videos integrationId=${params.integrationId} minDate=${params.minDate} maxResults=${params.maxResults}"
    )
    val videos = ZActivityStub.execute(
      youtubeActivities.fetchVideos(
        FetchVideosParams(
          integrationId = params.integrationId,
          minDate = params.minDate,
          maxResults = params.maxResults
        )
      )
    )

    if (videos.values.isEmpty) {
      logger.info("No new videos found")
      PullingResult(0)
    } else {
      val videosCount = videos.values.size
      logger.info(s"Going to store $videosCount videos...")
      ZActivityStub.execute(
        databaseActivities.storeVideos(
          videos = YoutubeVideosList(videos.values),
          params = StoreVideosParameters(
            integrationId = params.integrationId
          )
        )
      )
      PullingResult(videosCount)
    }
  }
}
