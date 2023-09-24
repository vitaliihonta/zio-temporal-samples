package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.ContentFeedIntegrationType
import zio._
import dev.vhonta.content.puller.workflows.PullConfigurationActivitiesImpl
import dev.vhonta.content.puller.workflows.base.{ScheduledPullerStarter, ScheduledPullerStarterImpl}
import dev.vhonta.content.puller.workflows.storage.{DatabaseActivitiesImpl, DatalakeActivitiesImpl}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository, PullerStateRepository}
import dev.vhonta.content.youtube.{OAuth2Client, YoutubeClient}
import zio.temporal.activity.{ZActivityImplementationObject, ZActivityRunOptions}
import zio.temporal.schedules.ZScheduleClient
import zio.temporal.worker.{ZWorker, ZWorkerFactory}
import zio.temporal.workflow.ZWorkflowImplementationClass

object YoutubeModule {
  val taskQueue: String = "content-youtube-pullers"

  private val makeStarter: URLayer[ZScheduleClient, ScheduledPullerStarter] =
    ZLayer.fromFunction(
      ScheduledPullerStarterImpl[YoutubeScheduledPullerWorkflow](
        _: ZScheduleClient,
        taskQueue = taskQueue,
        scheduleId = "content-pull-youtube-schedule",
        integrationType = ContentFeedIntegrationType.Youtube
      )
    )

  private val workflows = List(
    ZWorkflowImplementationClass[YoutubeScheduledPullerWorkflowImpl],
    ZWorkflowImplementationClass[YoutubePullWorkflowImpl]
  )

  private val activities = ZLayer.collectAll(
    List(
      ZActivityImplementationObject.layer(DatabaseActivitiesImpl.make),
      ZActivityImplementationObject.layer(DatalakeActivitiesImpl.make),
      ZActivityImplementationObject.layer(YoutubeActivitiesImpl.make),
      ZActivityImplementationObject.layer(PullConfigurationActivitiesImpl.make)
    )
  )

  val worker: RIO[
    ContentFeedRepository
      with ContentFeedIntegrationRepository
      with PullerStateRepository
      with ZActivityRunOptions[Any]
      with YoutubeClient
      with OAuth2Client
      with ZWorkerFactory
      with Scope,
    ZWorker
  ] =
    ZWorkerFactory.newWorker(taskQueue) @@
      ZWorker.addWorkflowImplementations(workflows) @@
      ZWorker.addActivityImplementationsLayer(activities)

  def start(reset: Boolean): ZIO[ZScheduleClient, Throwable, Unit] =
    ZIO
      .serviceWithZIO[ScheduledPullerStarter](_.start(reset))
      .provideSome[ZScheduleClient](makeStarter)
}
