package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.puller.proto.{PullingResult, PullerResetState, ScheduledPullerParams, PullerParams}
import zio.temporal._

trait BasePullWorkflow[Params <: PullerParams] {
  @workflowMethod
  def pull(params: Params): PullingResult
}

trait BaseScheduledPullerWorkflow[Params <: ScheduledPullerParams] {
  @workflowMethod
  def startPulling(params: Params): Unit

  @signalMethod
  def resetState(command: PullerResetState): Unit

  @signalMethod
  def resetStateAll(): Unit
}
