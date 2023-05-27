package dev.vhonta.content.puller

import dev.vhonta.content.puller.proto.InitialPullerState
import dev.vhonta.content.puller.workflows.NewsApiScheduledPullerWorkflow
import io.temporal.client.WorkflowExecutionAlreadyStarted
import zio._
import zio.temporal._
import zio.temporal.workflow._

object NewsApiScheduledPullerStarter {
  val TaskQueue   = "content-news-api-pullers"
  val SchedulerId = "content-news-api-scheduled-puller"

  val make: URLayer[ZWorkflowClient, NewsApiScheduledPullerStarter] =
    ZLayer.fromFunction(NewsApiScheduledPullerStarter(_))
}

case class NewsApiScheduledPullerStarter(client: ZWorkflowClient) {
  def start(reset: Boolean = false): Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Starting scheduler...")
      _ <- startPuller.catchSome { case _: WorkflowExecutionAlreadyStarted =>
             ZIO.when(reset)(resetPuller)
           }
      _ <- ZIO.logInfo("Scheduler started")
    } yield ()
  }

  private def resetPuller: Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Hard-reset scheduler")
      currentWorkflow <- client.newWorkflowStub[NewsApiScheduledPullerWorkflow](
                           NewsApiScheduledPullerStarter.SchedulerId
                         )
      _ <- currentWorkflow.terminate(reason = Some("Hard-reset"))
      _ <- startPuller
    } yield ()
  }

  private def startPuller: Task[Unit] = {
    for {
      scheduledPullerWorkflow <- client
                                   .newWorkflowStub[NewsApiScheduledPullerWorkflow]
                                   .withTaskQueue(NewsApiScheduledPullerStarter.TaskQueue)
                                   .withWorkflowId(NewsApiScheduledPullerStarter.SchedulerId)
                                   .withWorkflowExecutionTimeout(1.hour)
                                   .withRetryOptions(
                                     ZRetryOptions.default.withMaximumAttempts(2)
                                   )
                                   .build
      _ <- ZWorkflowStub.start(
             scheduledPullerWorkflow.startPulling(
               initialState = InitialPullerState(Nil)
             )
           )
    } yield ()
  }
}
