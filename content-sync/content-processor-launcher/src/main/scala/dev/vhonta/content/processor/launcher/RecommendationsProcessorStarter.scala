package dev.vhonta.content.processor.launcher

import dev.vhonta.content.processor.launcher.workflow.ScheduledRecommendationsWorkflow
import io.temporal.client.WorkflowExecutionAlreadyStarted
import zio._
import zio.temporal._
import zio.temporal.workflow._

object RecommendationsProcessorStarter {
  val TaskQueue   = "content-processors"
  val SchedulerId = "content-processor"

  val make: URLayer[ZWorkflowClient, RecommendationsProcessorStarter] =
    ZLayer.fromFunction(RecommendationsProcessorStarter(_))
}

case class RecommendationsProcessorStarter(client: ZWorkflowClient) {
  def start(reset: Boolean = false): Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Starting processor...")
      _ <- startRecommendationsWorkflow.catchSome { case _: WorkflowExecutionAlreadyStarted =>
             ZIO.when(reset)(resetWorkflow)
           }
      _ <- ZIO.logInfo("Processor started")
    } yield ()
  }

  private def resetWorkflow: Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Hard-reset scheduler")
      currentWorkflow <- client.newWorkflowStub[ScheduledRecommendationsWorkflow](
                           RecommendationsProcessorStarter.SchedulerId
                         )
      _ <- currentWorkflow.terminate(reason = Some("Hard-reset"))
      _ <- startRecommendationsWorkflow
    } yield ()
  }

  private def startRecommendationsWorkflow: Task[Unit] = {
    for {
      scheduledRecommendationsWorkflow <- client
                                            .newWorkflowStub[ScheduledRecommendationsWorkflow]
                                            .withTaskQueue(RecommendationsProcessorStarter.TaskQueue)
                                            .withWorkflowId(RecommendationsProcessorStarter.SchedulerId)
                                            .withWorkflowExecutionTimeout(1.hour)
                                            .withRetryOptions(
                                              ZRetryOptions.default.withMaximumAttempts(2)
                                            )
                                            .build
      _ <- ZWorkflowStub.start(
             scheduledRecommendationsWorkflow.makeRecommendations()
           )
    } yield ()
  }
}
