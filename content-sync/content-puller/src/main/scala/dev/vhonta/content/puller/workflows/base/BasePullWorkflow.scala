package dev.vhonta.content.puller.workflows.base

import dev.vhonta.content.puller.proto.{PullingResult, PullerParams}
import zio.temporal._

trait BasePullWorkflow[Params <: PullerParams] {
  @workflowMethod
  def pull(params: Params): PullingResult
}

trait BaseScheduledPullerWorkflow {
  @workflowMethod
  def pullAll(): Unit
}
