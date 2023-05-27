package dev.vhonta.content.tgbot

import dev.vhonta.content.tgbot.workflow.ScheduledPushRecommendationsWorkflow
import io.temporal.client.WorkflowExecutionAlreadyStarted
import zio._
import zio.temporal._
import zio.temporal.workflow._

object ScheduledPushStarter {
  val TaskQueue   = "telegram-push"
  val SchedulerId = "telegram-scheduled-push"

  val make: URLayer[ZWorkflowClient, ScheduledPushStarter] =
    ZLayer.fromFunction(ScheduledPushStarter(_))
}

case class ScheduledPushStarter(client: ZWorkflowClient) {
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
      currentWorkflow <- client.newWorkflowStub[ScheduledPushRecommendationsWorkflow](
                           ScheduledPushStarter.SchedulerId
                         )
      _ <- currentWorkflow.terminate(reason = Some("Hard-reset"))
      _ <- startPuller
    } yield ()
  }

  private def startPuller: Task[Unit] = {
    for {
      pushWorkflow <- client
                        .newWorkflowStub[ScheduledPushRecommendationsWorkflow]
                        .withTaskQueue(ScheduledPushStarter.TaskQueue)
                        .withWorkflowId(ScheduledPushStarter.SchedulerId)
                        .withWorkflowExecutionTimeout(1.hour)
                        .withRetryOptions(
                          ZRetryOptions.default.withMaximumAttempts(2)
                        )
                        .build
      _ <- ZWorkflowStub.start(
             pushWorkflow.start()
           )
    } yield ()
  }
}
