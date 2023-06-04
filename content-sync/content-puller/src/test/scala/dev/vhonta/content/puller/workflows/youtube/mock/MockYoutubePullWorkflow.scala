package dev.vhonta.content.puller.workflows.youtube.mock

import dev.vhonta.content.puller.proto.{PullingResult, YoutubePullerParameters}
import dev.vhonta.content.puller.workflows.youtube.YoutubePullWorkflow

case class MockYoutubePullWorkflow(result: () => PullingResult) extends YoutubePullWorkflow {
  override def pull(params: YoutubePullerParameters): PullingResult =
    result()
}
