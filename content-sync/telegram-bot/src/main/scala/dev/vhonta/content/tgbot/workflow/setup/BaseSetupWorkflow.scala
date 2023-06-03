package dev.vhonta.content.tgbot.workflow.setup

import dev.vhonta.content.tgbot.proto.{SetupResult, SetupParams}
import zio.temporal._

trait BaseSetupWorkflow[Params <: SetupParams] {
  @workflowMethod
  def setup(params: Params): SetupResult
}
