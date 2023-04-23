package dev.vhonta.news.puller.workflows

import dev.vhonta.news.puller.InitialPullerState
import io.temporal.client.WorkflowExecutionAlreadyStarted
import zio._
import zio.temporal._
import zio.temporal.workflow._

object ScheduledPullerStarter {
  val TaskQueue   = "pullers"
  val SchedulerId = "scheduled-puller"

  val make: URLayer[ZWorkflowClient, ScheduledPullerStarter] =
    ZLayer.fromFunction(ScheduledPullerStarter(_))
}

case class ScheduledPullerStarter(client: ZWorkflowClient) {
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
      currentWorkflow <- client
                           .newWorkflowStub[ScheduledPullerWorkflow](ScheduledPullerStarter.SchedulerId)
      _ <- currentWorkflow.terminate(reason = Some("Hard-reset"))
      _ <- startPuller
    } yield ()
  }

  private def startPuller: Task[Unit] = {
    for {
      scheduledPullerWorkflow <- client
                                   .newWorkflowStub[ScheduledPullerWorkflow]
                                   .withTaskQueue(ScheduledPullerStarter.TaskQueue)
                                   .withWorkflowId(ScheduledPullerStarter.SchedulerId)
                                   .withWorkflowExecutionTimeout(2.hours)
                                   .withRetryOptions(
                                     ZRetryOptions.default.withMaximumAttempts(3)
                                   )
                                   .build
      _ <- ZWorkflowStub
             .start(
               scheduledPullerWorkflow.startPulling(
                 initialState = InitialPullerState(Nil)
               )
             )
    } yield ()
  }
}
