package dev.vhonta.content.processor.launcher

import dev.vhonta.content.processor.launcher.workflow.{ProcessorConfiguration, ProcessorLauncherWorkflow}
import io.temporal.client.WorkflowExecutionAlreadyStarted
import zio._
import zio.temporal._
import zio.temporal.workflow._

object ProcessorLauncherStarter {
  val TaskQueue   = "processor-launcher"
  val SchedulerId = "processor-launcher"

  val make: ZLayer[ZWorkflowClient, Config.Error, ProcessorLauncherStarter] = {
    ZLayer.fromZIO(ZIO.config(ProcessorConfiguration.definition)) >>>
      ZLayer.fromFunction(ProcessorLauncherStarter(_: ZWorkflowClient, _: ProcessorConfiguration))
  }
}

case class ProcessorLauncherStarter(client: ZWorkflowClient, config: ProcessorConfiguration) {
  def start(reset: Boolean = false): Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Starting processor launcher...")
      _ <- startRecommendationsWorkflow.catchSome { case _: WorkflowExecutionAlreadyStarted =>
             ZIO.when(reset)(resetWorkflow)
           }
      _ <- ZIO.logInfo("Processor launcher started")
    } yield ()
  }

  private def resetWorkflow: Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Hard-reset launcher")
      currentWorkflow <- client.newWorkflowStub[ProcessorLauncherWorkflow](
                           ProcessorLauncherStarter.SchedulerId
                         )
      _ <- currentWorkflow.terminate(reason = Some("Hard-reset"))
      _ <- startRecommendationsWorkflow
    } yield ()
  }

  private def startRecommendationsWorkflow: Task[Unit] = {
    for {
      launcherWorkflow <- client
                            .newWorkflowStub[ProcessorLauncherWorkflow]
                            .withTaskQueue(ProcessorLauncherStarter.TaskQueue)
                            .withWorkflowId(ProcessorLauncherStarter.SchedulerId)
                            .withWorkflowExecutionTimeout(config.processInterval * 1.25)
                            .withRetryOptions(
                              ZRetryOptions.default.withMaximumAttempts(2)
                            )
                            .build
      _ <- ZWorkflowStub.start(
             launcherWorkflow.launch()
           )
    } yield ()
  }
}
