package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.puller.proto.{PullingResult, YoutubePullerResetState}
import scalapb.GeneratedMessage
import zio.temporal._

trait BasePullWorkflow[Params <: GeneratedMessage] {
  @workflowMethod
  def pull(params: Params): PullingResult
}

trait BaseScheduledPullerWorkflow[InitialState <: GeneratedMessage] {
  @workflowMethod
  def startPulling(initialState: InitialState): Unit

  @signalMethod
  def resetState(command: YoutubePullerResetState /*TODO: rename to generic*/ ): Unit

  @signalMethod
  def resetStateAll(): Unit
}
