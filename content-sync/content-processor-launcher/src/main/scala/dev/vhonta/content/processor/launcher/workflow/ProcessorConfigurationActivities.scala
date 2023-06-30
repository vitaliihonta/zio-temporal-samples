package dev.vhonta.content.processor.launcher.workflow

import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.processor.proto

case class ProcessorConfiguration(
  processInterval:      Duration,
  singleProcessTimeout: Duration)

object ProcessorConfiguration {
  val definition: Config[ProcessorConfiguration] =
    (Config.duration("process_interval") ++
      Config.duration("single_process_timeout"))
      .nested("recommendations")
      .map((ProcessorConfiguration.apply _).tupled)
}

@activityInterface
trait ProcessorConfigurationActivities {
  def getProcessorConfiguration: proto.ProcessorConfiguration
}

object ProcessorConfigurationActivitiesImpl {
  val make: URLayer[ZActivityOptions[Any], ProcessorConfigurationActivities] =
    ZLayer.fromFunction(new ProcessorConfigurationActivitiesImpl()(_: ZActivityOptions[Any]))
}

class ProcessorConfigurationActivitiesImpl()(implicit options: ZActivityOptions[Any])
    extends ProcessorConfigurationActivities {
  override def getProcessorConfiguration: proto.ProcessorConfiguration =
    ZActivity.run {
      ZIO
        .config(ProcessorConfiguration.definition)
        .map(pushConfig =>
          proto.ProcessorConfiguration(
            processInterval = pushConfig.processInterval.toProto,
            singleProcessTimeout = pushConfig.singleProcessTimeout.toProto
          )
        )
    }
}
