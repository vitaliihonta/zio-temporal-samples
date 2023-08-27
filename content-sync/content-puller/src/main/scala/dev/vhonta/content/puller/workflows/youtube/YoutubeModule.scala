package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.ContentFeedIntegrationType
import zio._
import dev.vhonta.content.puller.workflows.PullConfigurationActivities
import dev.vhonta.content.puller.workflows.base.{ScheduledPullerStarter, ScheduledPullerStarterImpl}
import dev.vhonta.content.puller.workflows.storage.{DatabaseActivities, DatalakeActivities}
import zio.temporal.schedules.ZScheduleClient
import zio.temporal.worker.{ZWorker, ZWorkerFactory}

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

  val worker: ZIO[
    PullConfigurationActivities
      with YoutubeActivities
      with DatabaseActivities
      with DatalakeActivities
      with ZWorkerFactory,
    Nothing,
    ZWorker
  ] =
    ZWorkerFactory.newWorker(taskQueue) @@
      ZWorker.addWorkflow[YoutubeScheduledPullerWorkflowImpl].fromClass @@
      ZWorker.addWorkflow[YoutubePullWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationService[DatabaseActivities] @@
      ZWorker.addActivityImplementationService[DatalakeActivities] @@
      ZWorker.addActivityImplementationService[YoutubeActivities] @@
      ZWorker.addActivityImplementationService[PullConfigurationActivities]

  def start(reset: Boolean): ZIO[ZScheduleClient, Throwable, Unit] =
    ZIO
      .serviceWithZIO[ScheduledPullerStarter](_.start(reset))
      .provideSome[ZScheduleClient](makeStarter)
}
