package dev.vhonta.content.tgbot.workflow.push

import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.tgbot.proto

case class PushConfiguration(
  pushInterval:      Duration,
  singlePushTimeout: Duration)

object PushConfiguration {
  val definition: Config[PushConfiguration] =
    (Config.duration("push_interval") ++
      Config.duration("single_push_timeout"))
      .nested("telegram", "push")
      .map((PushConfiguration.apply _).tupled)
}

@activityInterface
trait PushConfigurationActivities {
  def getPushConfiguration: proto.PushConfiguration
}

object PushConfigurationActivitiesImpl {
  val make: URLayer[ZActivityRunOptions[Any], PushConfigurationActivities] =
    ZLayer.fromFunction(new PushConfigurationActivitiesImpl()(_: ZActivityRunOptions[Any]))
}

class PushConfigurationActivitiesImpl()(implicit options: ZActivityRunOptions[Any])
    extends PushConfigurationActivities {
  override def getPushConfiguration: proto.PushConfiguration =
    ZActivity.run {
      ZIO
        .config(PushConfiguration.definition)
        .map(pushConfig =>
          proto.PushConfiguration(
            pushInterval = pushConfig.pushInterval.toProto,
            singlePushTimeout = pushConfig.singlePushTimeout.toProto
          )
        )
    }
}
