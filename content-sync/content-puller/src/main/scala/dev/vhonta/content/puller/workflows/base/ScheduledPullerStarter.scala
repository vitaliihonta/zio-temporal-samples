package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.ContentFeedIntegrationType
import dev.vhonta.content.puller.PullerConfig
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import io.temporal.client.schedules.ScheduleAlreadyRunningException
import zio._
import zio.temporal._
import zio.temporal.schedules._
import zio.temporal.workflow._
import scala.reflect.ClassTag

sealed trait ScheduledPullerStarter {
  def start(reset: Boolean): Task[Unit]
}

case class ScheduledPullerStarterImpl[ScheduledWorkflow <: BaseScheduledPullerWorkflow: IsWorkflow: ClassTag](
  scheduleClient:  ZScheduleClient,
  taskQueue:       String,
  scheduleId:      String,
  integrationType: ContentFeedIntegrationType)
    extends ScheduledPullerStarter {

  private val stub = scheduleClient
    .newScheduleStartWorkflowStub[ScheduledWorkflow]()
    .withTaskQueue(taskQueue)
    .withWorkflowId(s"scheduled-$integrationType")
    .withWorkflowExecutionTimeout(1.hour)
    .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(2))
    .build

  override def start(reset: Boolean): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Starting $integrationType schedule...")
      _ <- schedulePuller.catchSome { case _: ScheduleAlreadyRunningException =>
             ZIO.when(reset)(resetSchedule)
           }
      _ <- ZIO.logInfo(s"Schedule for integration=$integrationType started")
    } yield ()
  }

  private def resetSchedule: Task[Unit] = {
    for {
      _               <- ZIO.logInfo(s"Hard-reset $integrationType scheduler")
      currentSchedule <- scheduleClient.getHandle(scheduleId)
      _               <- currentSchedule.delete()
      _               <- schedulePuller
    } yield ()
  }

  private def schedulePuller: Task[Unit] = {
    for {
      config <- ZIO.config(PullerConfig.definition.nested("puller", integrationType.entryName))

      schedule = ZSchedule
                   .withAction(
                     ZScheduleStartWorkflowStub.start(
                       stub.pullAll()
                     )
                   )
                   .withSpec(
                     ZScheduleSpec.intervals(
                       every(config.pullInterval)
                     )
                   )
                   .withPolicy(ZSchedulePolicy.default.withOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP))

      _ <- scheduleClient.createSchedule(
             scheduleId,
             schedule = schedule,
             options = ZScheduleOptions.default.withTriggerImmediately(true)
           )

    } yield ()
  }
}
