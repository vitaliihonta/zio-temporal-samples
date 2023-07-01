package dev.vhonta.content.processor.launcher.workflow

import dev.vhonta.content.processor.proto.SparkLaunchLocalParams
import zio._
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.failure.ApplicationFailure
import zio.temporal.protobuf.syntax._
import zio.temporal.workflow._

@workflowInterface
trait ProcessorLauncherWorkflow {
  @workflowMethod
  def launch(): Unit
}

class ProcessorLauncherWorkflowImpl extends ProcessorLauncherWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val configurationActivities = ZWorkflow
    .newActivityStub[ProcessorConfigurationActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default.withDoNotRetry(
        nameOf[Config.Error]
      )
    )
    .build

  private val launcherActivity = ZWorkflow
    .newActivityStub[ProcessorLauncherActivity]
    .withStartToCloseTimeout(1.hour)
    .withHeartbeatTimeout(1.minute)
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[ProcessorLauncherWorkflow].build

  override def launch(): Unit = {
    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val processorConfig = ZActivityStub.execute(
      configurationActivities.getProcessorConfiguration
    )

    logger.info("Starting processor job locally")
    try {
      ZActivityStub.execute(
        launcherActivity.launchProcessorJob(
          SparkLaunchLocalParams(jobTimeout = processorConfig.jobTimeout)
        )
      )
    } catch {
      case apf: ApplicationFailure =>
        logger.error(s"Launcher activity failed, ignoring", apf)
    }

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime = processorConfig.processInterval.fromProto[Duration] minus
      java.time.Duration.between(startedAt, finishedAt)

    logger.info(s"Next processing starts after $sleepTime")

    // Wait for the next run
    ZWorkflow.sleep(sleepTime)

    // Continue as new workflow
    ZWorkflowContinueAsNewStub.execute(
      nextRun.launch()
    )
  }
}
