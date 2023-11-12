package dev.vhonta.ukvi.visa

import zio._
import zio.temporal._
import zio.temporal.activity._

case class ApplicationTimeouts(
  globalFormTimeout:        Duration,
  emailConfirmationTimeout: Duration)

@activityInterface
trait ConfigurationActivities {
  def loadApplicationTimeouts(): ApplicationTimeouts
}

object ConfigurationActivities {
  val make: URLayer[ZActivityRunOptions[Any], ConfigurationActivities] =
    ZLayer.derive[ConfigurationActivitiesImpl]
}

class ConfigurationActivitiesImpl()(implicit options: ZActivityRunOptions[Any]) extends ConfigurationActivities {
  private val applicationFormConfig =
    (
      Config.duration("global-form-timeout") ++
        Config.duration("email-confirmation-timeout")
    ).nested("visas", "visitor")
      .map(ApplicationTimeouts.tupled)

  override def loadApplicationTimeouts(): ApplicationTimeouts = {
    ZActivity.run {
      ZIO.logInfo("Loading application form timeout...") *>
        ZIO.config(applicationFormConfig)
    }
  }
}
