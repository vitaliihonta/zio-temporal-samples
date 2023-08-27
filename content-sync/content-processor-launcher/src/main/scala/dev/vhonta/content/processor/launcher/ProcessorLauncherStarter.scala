package dev.vhonta.content.processor.launcher

import dev.vhonta.content.processor.launcher.workflow.{ProcessorConfiguration, ProcessorLauncherWorkflow}
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import zio.temporal.schedules._
import io.temporal.client.schedules.ScheduleAlreadyRunningException
import zio._
import zio.temporal._
import zio.temporal.schedules.ZScheduleClient

object ProcessorLauncherStarter {
  val TaskQueue  = "processor-launcher"
  val ScheduleId = "processor-launcher"

  val make: ZLayer[ZScheduleClient, Config.Error, ProcessorLauncherStarter] = {
    ZLayer.fromZIO(ZIO.config(ProcessorConfiguration.definition)) >>>
      ZLayer.fromFunction(ProcessorLauncherStarter(_: ZScheduleClient, _: ProcessorConfiguration))
  }
}

case class ProcessorLauncherStarter(scheduleClient: ZScheduleClient, config: ProcessorConfiguration) {
  private val stub = scheduleClient
    .newScheduleStartWorkflowStub[ProcessorLauncherWorkflow]()
    .withTaskQueue(ProcessorLauncherStarter.TaskQueue)
    .withWorkflowId(ProcessorLauncherStarter.ScheduleId + "-schedule")
    .withWorkflowExecutionTimeout(config.processInterval * 1.25)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(2)
    )
    .build

  def start(reset: Boolean = false): Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Starting processor launcher...")
      // todo: should be catchSome
      _ <- scheduleRecommendationsWorkflow.catchSomeDefect { case _: ScheduleAlreadyRunningException =>
             ZIO.when(reset)(resetSchedule)
           }
      _ <- ZIO.logInfo("Processor launcher started")
    } yield ()
  }

  private def resetSchedule: Task[Unit] = {
    for {
      _               <- ZIO.logInfo("Hard-reset launcher")
      currentSchedule <- scheduleClient.getHandle(ProcessorLauncherStarter.ScheduleId)
      _               <- currentSchedule.delete()
      _               <- scheduleRecommendationsWorkflow
    } yield ()
  }

  private def scheduleRecommendationsWorkflow: Task[Unit] = {
    val schedule = ZSchedule
      .withAction(
        ZScheduleStartWorkflowStub.start(
          stub.launch()
        )
      )
      .withSpec(
        ZScheduleSpec.intervals(
          every(config.processInterval)
        )
      )
      .withPolicy(ZSchedulePolicy.default.withOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP))

    scheduleClient
      .createSchedule(
        ProcessorLauncherStarter.ScheduleId,
        schedule = schedule,
        options = ZScheduleOptions.default.withTriggerImmediately(true)
      )
      .unit
  }
}
