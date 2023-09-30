import mill._, mill.scalalib._, mill.scalalib.scalafmt._
import $ivy.`com.lihaoyi::mill-contrib-scalapblib:$MILL_VERSION`, contrib.scalapblib._

object versions {
  val scala3      = "3.3.0"
  val zio         = "2.0.18"
  val ziologging  = "2.1.14"
  val ziotemporal = "0.6.0"
  val logback     = "1.4.8"
}

object cryptostock extends SbtModule with ScalafmtModule with ScalaPBModule {
  def scalaVersion = versions.scala3

  def ivyDeps = Agg(
    ivy"dev.zio::zio:${versions.zio}",
    ivy"dev.zio::zio-logging:${versions.ziologging}",
    ivy"dev.zio::zio-logging-slf4j2:${versions.ziologging}",
    ivy"dev.vhonta::zio-temporal-core:${versions.ziotemporal}",
    ivy"dev.vhonta::zio-temporal-protobuf:${versions.ziotemporal}",
    ivy"ch.qos.logback:logback-classic:${versions.logback}"
  )
  object test extends TestModule.ZioTest {
    def ivyDeps = Agg(
      ivy"dev.zio::zio-test:${versions.zio}",
      ivy"dev.zio::zio-test-sbt:${versions.zio}",
      ivy"dev.vhonta::zio-temporal-testkit:${versions.ziotemporal}"
    )
  }
  def scalaPBVersion = "0.11.13"
  def scalaPBSources = T.sources {
    millSourcePath / "src" / "main" / "protobuf"
  }
  def scalaPBSearchDeps  = true
  def scalaPBFlatPackage = true
  def scalaPBGrpc        = false
}
