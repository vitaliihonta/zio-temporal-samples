package dev.vhonta.content.puller.workflows.youtube

import zio._
import dev.vhonta.content.puller.proto.YoutubePullerInitialState
import dev.vhonta.content.puller.workflows.{PullConfigurationActivities, DatabaseActivities}
import dev.vhonta.content.puller.workflows.base.{ScheduledPullerStarter, ScheduledPullerStarterImpl}
import zio._
import zio.temporal.worker.{ZWorker, ZWorkerFactory}
import zio.temporal.workflow.ZWorkflowClient

object YoutubeModule {
  val taskQueue: String = "content-youtube-pullers"

  private val makeStarter: URLayer[ZWorkflowClient, ScheduledPullerStarter] =
    ZLayer.fromFunction(
      ScheduledPullerStarterImpl[YoutubePullerInitialState, YoutubeScheduledPullerWorkflow](
        _: ZWorkflowClient,
        taskQueue = taskQueue,
        schedulerId = "content-youtube-scheduled-puller",
        initialParams = YoutubePullerInitialState(Nil)
      )
    )

  val worker: ZIO[
    PullConfigurationActivities with YoutubeActivities with DatabaseActivities with ZWorkerFactory,
    Nothing,
    ZWorker
  ] =
    ZWorkerFactory.newWorker(taskQueue) @@
      ZWorker.addWorkflow[YoutubeScheduledPullerWorkflowImpl].fromClass @@
      ZWorker.addWorkflow[YoutubePullWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationService[DatabaseActivities] @@
      ZWorker.addActivityImplementationService[YoutubeActivities] @@
      ZWorker.addActivityImplementationService[PullConfigurationActivities]

  def start(reset: Boolean): ZIO[ZWorkflowClient, Throwable, Unit] =
    ZIO
      .serviceWithZIO[ScheduledPullerStarter](_.start(reset))
      .provideSome[ZWorkflowClient](makeStarter)
}
