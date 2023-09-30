package dev.vhonta.content.tgbot

import dev.vhonta.content.tgbot.workflow.push.{PushConfiguration, ScheduledPushRecommendationsWorkflow}
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import io.temporal.client.schedules.ScheduleAlreadyRunningException
import zio._
import zio.temporal._
import zio.temporal.schedules._
import zio.temporal.workflow.ZWorkflowOptions

object ScheduledPushStarter {
  val ScheduleId = "telegram-scheduled-push"

  val make: URLayer[ZScheduleClient, ScheduledPushStarter] =
    ZLayer.fromFunction(ScheduledPushStarter(_))
}

case class ScheduledPushStarter(scheduleClient: ZScheduleClient) {

  private val stub = scheduleClient.newScheduleStartWorkflowStub[ScheduledPushRecommendationsWorkflow](
    ZWorkflowOptions
      .withWorkflowId(ScheduledPushStarter.ScheduleId)
      .withTaskQueue(TelegramModule.TaskQueue)
      .withWorkflowExecutionTimeout(1.hour)
      .withRetryOptions(
        ZRetryOptions.default.withMaximumAttempts(2)
      )
  )

  def start(reset: Boolean = false): Task[Unit] = {
    for {
      _ <- ZIO.logInfo("Starting scheduler...")
      _ <- schedulePush.catchSome { case _: ScheduleAlreadyRunningException =>
             ZIO.when(reset)(resetSchedule)
           }
      _ <- ZIO.logInfo("Scheduler started")
    } yield ()
  }

  private def resetSchedule: Task[Unit] = {
    for {
      _               <- ZIO.logInfo("Hard-reset scheduler")
      currentSchedule <- scheduleClient.getHandle(ScheduledPushStarter.ScheduleId)
      _               <- currentSchedule.delete()
      _               <- schedulePush
    } yield ()
  }

  private def schedulePush: Task[Unit] = {
    for {
      config <- ZIO.config(PushConfiguration.definition)

      schedule =
        ZSchedule
          .withAction(
            ZScheduleStartWorkflowStub.start(
              stub.start()
            )
          )
          .withSpec(
            ZScheduleSpec.intervals(
              every(config.pushInterval)
            )
          )
          .withPolicy(ZSchedulePolicy.default.withOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP))

      _ <- scheduleClient
             .createSchedule(
               ScheduledPushStarter.ScheduleId,
               schedule = schedule,
               options = ZScheduleOptions.default.withTriggerImmediately(true)
             )
    } yield ()
  }
}
