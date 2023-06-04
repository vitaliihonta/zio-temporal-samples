package dev.vhonta.content.puller

import zio._

case class PullerConfig(
  pullInterval:      Duration,
  singlePullTimeout: Duration)

object PullerConfig {
  val definition: Config[PullerConfig] =
    (Config.duration("pull_interval") ++ Config.duration("single_pull_timeout"))
      .map((PullerConfig.apply _).tupled)
}

case class YoutubePullerConfig(maxResults: Int)
object YoutubePullerConfig {
  val definition: Config[YoutubePullerConfig] =
    Config.int("max_results").map(YoutubePullerConfig(_))
}
