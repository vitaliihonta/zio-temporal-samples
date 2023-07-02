package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.puller.proto.NewsApiInitialPullerState
import dev.vhonta.content.puller.workflows.{DatabaseActivities, DatalakeActivities, PullConfigurationActivities}
import dev.vhonta.content.puller.workflows.base.{ScheduledPullerStarter, ScheduledPullerStarterImpl}
import zio._
import zio.temporal.worker.{ZWorker, ZWorkerFactory}
import zio.temporal.workflow.ZWorkflowClient

object NewsApiModule {
  val taskQueue: String = "content-news-api-pullers"

  private val makeStarter: URLayer[ZWorkflowClient, ScheduledPullerStarter] =
    ZLayer.fromFunction(
      ScheduledPullerStarterImpl[NewsApiInitialPullerState, NewsApiScheduledPullerWorkflow](
        _: ZWorkflowClient,
        taskQueue = taskQueue,
        schedulerId = "content-news-api-scheduled-puller",
        initialParams = NewsApiInitialPullerState(Nil)
      )
    )

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

  def start(reset: Boolean): ZIO[ZWorkflowClient, Throwable, Unit] =
    ZIO
      .serviceWithZIO[ScheduledPullerStarter](_.start(reset))
      .provideSome[ZWorkflowClient](makeStarter)
}
