package dev.vhonta.content.processor.launcher.workflow

import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.processor.proto

case class ProcessorConfiguration(
  processInterval:    Duration,
  jobTimeout:         Duration,
  inputPath:          String,
  checkpointLocation: String,
  resultPath:         String)

object ProcessorConfiguration {
  val definition: Config[ProcessorConfiguration] =
    (Config.duration("process_interval") ++
      Config.duration("job_timeout") ++
      Config.string("input_path") ++
      Config.string("checkpoint_location") ++
      Config.string("result_path"))
      .nested("processor", "launcher")
      .map((ProcessorConfiguration.apply _).tupled)
}

@activityInterface
trait ProcessorConfigurationActivities {
  def getProcessorConfiguration: proto.ProcessorConfiguration
}

object ProcessorConfigurationActivitiesImpl {
  val make: URLayer[ZActivityRunOptions[Any], ProcessorConfigurationActivities] =
    ZLayer.fromFunction(new ProcessorConfigurationActivitiesImpl()(_: ZActivityRunOptions[Any]))
}

class ProcessorConfigurationActivitiesImpl()(implicit options: ZActivityRunOptions[Any])
    extends ProcessorConfigurationActivities {
  override def getProcessorConfiguration: proto.ProcessorConfiguration =
    ZActivity.run {
      ZIO
        .config(ProcessorConfiguration.definition)
        .map(config =>
          proto.ProcessorConfiguration(
            processInterval = config.processInterval.toProto,
            jobTimeout = config.jobTimeout.toProto,
            inputPath = config.inputPath,
            checkpointLocation = config.checkpointLocation,
            resultPath = config.resultPath
          )
        )
    }
}
