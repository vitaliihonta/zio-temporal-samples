package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.ContentFeedIntegrationType
import dev.vhonta.content.puller.workflows.PullConfigurationActivities
import dev.vhonta.content.puller.workflows.base.{ScheduledPullerStarter, ScheduledPullerStarterImpl}
import dev.vhonta.content.puller.workflows.storage.{DatabaseActivities, DatalakeActivities}
import zio._
import zio.temporal.schedules.ZScheduleClient
import zio.temporal.worker.{ZWorker, ZWorkerFactory}

object NewsApiModule {
  val taskQueue: String = "content-news-api-pullers"

  private val makeStarter: URLayer[ZScheduleClient, ScheduledPullerStarter] =
    ZLayer.fromFunction(
      ScheduledPullerStarterImpl[NewsApiScheduledPullerWorkflow](
        _: ZScheduleClient,
        taskQueue = taskQueue,
        scheduleId = "content-pull-news-api-schedule",
        integrationType = ContentFeedIntegrationType.NewsApi
      )
    )

  // todo: use layers
  val worker: ZIO[
    PullConfigurationActivities with NewsActivities with DatabaseActivities with DatalakeActivities with ZWorkerFactory,
    Nothing,
    ZWorker
  ] =
    ZWorkerFactory.newWorker(taskQueue) @@
      ZWorker.addWorkflow[NewsApiScheduledPullerWorkflowImpl].fromClass @@
      ZWorker.addWorkflow[NewsApiPullWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationService[DatabaseActivities] @@
      ZWorker.addActivityImplementationService[DatalakeActivities] @@
      ZWorker.addActivityImplementationService[NewsActivities] @@
      ZWorker.addActivityImplementationService[PullConfigurationActivities]

  def start(reset: Boolean): ZIO[ZScheduleClient, Throwable, Unit] =
    ZIO
      .serviceWithZIO[ScheduledPullerStarter](_.start(reset))
      .provideSome[ZScheduleClient](makeStarter)
}
