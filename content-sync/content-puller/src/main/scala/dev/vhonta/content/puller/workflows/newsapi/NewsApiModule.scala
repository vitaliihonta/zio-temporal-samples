package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.ContentFeedIntegrationType
import dev.vhonta.content.newsapi.NewsApiClient
import dev.vhonta.content.puller.workflows.PullConfigurationActivitiesImpl
import dev.vhonta.content.puller.workflows.base.{ScheduledPullerStarter, ScheduledPullerStarterImpl}
import dev.vhonta.content.puller.workflows.storage.{DatabaseActivitiesImpl, DatalakeActivitiesImpl}
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository, PullerStateRepository}
import zio._
import zio.temporal.activity.{ZActivityImplementationObject, ZActivityRunOptions}
import zio.temporal.schedules.ZScheduleClient
import zio.temporal.worker.{ZWorker, ZWorkerFactory}
import zio.temporal.workflow.ZWorkflowImplementationClass

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

  private val workflows = List(
    ZWorkflowImplementationClass[NewsApiScheduledPullerWorkflowImpl],
    ZWorkflowImplementationClass[NewsApiPullWorkflowImpl]
  )

  private val activities = ZLayer.collectAll(
    List(
      ZActivityImplementationObject.layer(DatabaseActivitiesImpl.make),
      ZActivityImplementationObject.layer(DatalakeActivitiesImpl.make),
      ZActivityImplementationObject.layer(NewsActivitiesImpl.make),
      ZActivityImplementationObject.layer(PullConfigurationActivitiesImpl.make)
    )
  )

  val worker: RIO[
    ContentFeedRepository
      with ContentFeedIntegrationRepository
      with PullerStateRepository
      with ZActivityRunOptions[Any]
      with NewsApiClient
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
