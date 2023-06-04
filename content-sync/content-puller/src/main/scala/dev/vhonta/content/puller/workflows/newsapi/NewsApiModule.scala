package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.puller.proto.NewsApiInitialPullerState
import dev.vhonta.content.puller.workflows.{ConfigurationActivities, DatabaseActivities}
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
    ConfigurationActivities with NewsActivities with DatabaseActivities with ZWorkerFactory,
    Nothing,
    ZWorker
  ] =
    ZWorkerFactory.newWorker(taskQueue) @@
      ZWorker.addWorkflow[NewsApiScheduledPullerWorkflowImpl].fromClass @@
      ZWorker.addWorkflow[NewsApiPullWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationService[DatabaseActivities] @@
      ZWorker.addActivityImplementationService[NewsActivities] @@
      ZWorker.addActivityImplementationService[ConfigurationActivities]

  def start(reset: Boolean): ZIO[ZWorkflowClient, Throwable, Unit] =
    ZIO
      .serviceWithZIO[ScheduledPullerStarter](_.start(reset))
      .provideSome[ZWorkflowClient](makeStarter)
}
