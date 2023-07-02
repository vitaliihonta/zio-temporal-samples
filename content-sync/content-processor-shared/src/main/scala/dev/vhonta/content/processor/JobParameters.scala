package dev.vhonta.content.processor

import enumeratum.{Enum, EnumEntry}
import java.time.LocalDate
import scala.concurrent.duration._
import scopt._

sealed trait JobMode extends EnumEntry.Snakecase

object JobMode extends Enum[JobMode] {
  case object Submit extends JobMode
  case object Local  extends JobMode

  override val values = findValues
}

case class JobParameters(
  mode:               JobMode = JobMode.Local,
  date:               LocalDate = LocalDate.now(),
  runId:              String = "dev",
  inputPath:          String = "",
  checkpointLocation: String = "",
  resultPath:         String = "",
  timeout:            Duration = 50.minutes)

object JobParameters extends OptionParser[JobParameters]("contentProcessor") with Serializable {
  opt[String]("mode")
    .optional()
    .action((x, c) => c.copy(mode = JobMode.withName(x)))
    .withFallback(() => JobMode.Local.entryName)

  opt[String]("date")
    .optional()
    .action((x, c) => c.copy(date = LocalDate.parse(x)))
    .withFallback(() => LocalDate.now().toString)

  opt[String]("run-id")
    .optional()
    .action((x, c) => c.copy(runId = x))
    .withFallback(() => "dev")

  opt[String]("input-path")
    .optional()
    .action((x, c) => c.copy(inputPath = x))
    .withFallback(() => sys.env("PWD") + "/datalake")

  opt[String]("checkpoint-location")
    .optional()
    .action((x, c) => c.copy(checkpointLocation = x))
    .withFallback(() => sys.env("PWD") + "/checkpoint")

  opt[String]("result-path")
    .optional()
    .action((x, c) => c.copy(resultPath = x))
    .withFallback(() => sys.env("PWD") + "/processor-results")

  opt[Duration]("timeout")
    .optional()
    .action((x, c) => c.copy(timeout = x))
    .withFallback(() => 50.minutes)
}
