package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.puller.proto.ScheduledPullerParams
import io.temporal.client.WorkflowExecutionAlreadyStarted
import zio._
import zio.temporal._
import zio.temporal.workflow._
import scala.reflect.ClassTag

sealed trait ScheduledPullerStarter {
  def start(reset: Boolean): Task[Unit]
}

case class ScheduledPullerStarterImpl[
  Params <: ScheduledPullerParams,
  ScheduledWorkflow <: BaseScheduledPullerWorkflow[Params]: IsWorkflow: ClassTag
](client:        ZWorkflowClient,
  taskQueue:     String,
  schedulerId:   String,
  initialParams: Params)
    extends ScheduledPullerStarter {

  private val workflowType = implicitly[ClassTag[ScheduledWorkflow]].runtimeClass.getName

  override def start(reset: Boolean): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Starting $workflowType scheduler...")
      _ <- startPuller.catchSome { case _: WorkflowExecutionAlreadyStarted =>
             ZIO.when(reset)(resetPuller)
           }
      _ <- ZIO.logInfo(s"Scheduler $workflowType started")
    } yield ()
  }

  private def resetPuller: Task[Unit] = {
    for {
      _               <- ZIO.logInfo(s"Hard-reset $workflowType scheduler")
      currentWorkflow <- client.newWorkflowStub[ScheduledWorkflow](schedulerId)
      _               <- currentWorkflow.terminate(reason = Some("Hard-reset"))
      _               <- startPuller
    } yield ()
  }

  private def startPuller: Task[Unit] = {
    for {
      scheduledPullerWorkflow <- client
                                   .newWorkflowStub[ScheduledWorkflow]
                                   .withTaskQueue(taskQueue)
                                   .withWorkflowId(schedulerId)
                                   .withWorkflowExecutionTimeout(1.hour)
                                   .withRetryOptions(
                                     ZRetryOptions.default.withMaximumAttempts(2)
                                   )
                                   .build
      _ <- ZWorkflowStub.start(
             scheduledPullerWorkflow.startPulling(initialParams)
           )
    } yield ()
  }
}
