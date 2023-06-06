import sbt._

object Dependencies {
  private object versions {
    val zioTemporal = "0.2.0-RC4"
    val zio         = "2.0.12"
    val zioLogging  = "2.1.12"
    val zioConfig   = "4.0.0-RC14"
    val zioJson     = "0.5.0"
    val enumeratum  = "1.7.2"
    val sttp        = "3.8.15"
    val telegramium = "7.67.0"
  }

  val zioTemporal = Seq(
    "dev.vhonta" %% "zio-temporal-core"     % versions.zioTemporal,
    "dev.vhonta" %% "zio-temporal-protobuf" % versions.zioTemporal,
    "dev.vhonta" %% "zio-temporal-protobuf" % versions.zioTemporal % "protobuf",
    "dev.vhonta" %% "zio-temporal-testkit"  % versions.zioTemporal % Test,

    // protobuf libs
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion,
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  )

  val sttp = Seq(
    "com.softwaremill.sttp.client3" %% "core"     % versions.sttp,
    "com.softwaremill.sttp.client3" %% "zio"      % versions.sttp,
    "com.softwaremill.sttp.client3" %% "zio-json" % versions.sttp
  )

  val zioBase = Seq(
    "dev.zio"       %% "zio"                 % versions.zio,
    "dev.zio"       %% "zio-streams"         % versions.zio,
    "dev.zio"       %% "zio-json"            % versions.zioJson,
    "dev.zio"       %% "zio-logging"         % versions.zioLogging,
    "dev.zio"       %% "zio-logging-slf4j"   % versions.zioLogging,
    "dev.zio"       %% "zio-config"          % versions.zioConfig,
    "dev.zio"       %% "zio-config-typesafe" % versions.zioConfig,
    "ch.qos.logback" % "logback-classic"     % "1.2.11",
    "dev.zio"       %% "zio-test"            % versions.zio % Test,
    "dev.zio"       %% "zio-test-sbt"        % versions.zio % Test
  )

  val zioHttp = Seq(
    "dev.zio" %% "zio-http" % "3.0.0-RC1"
  )

  val mockito = Seq(
    "org.mockito" %% "mockito-scala" % "1.17.14" % Test
  )

  val zioTestFrameworks = Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

  val database = Seq(
    "io.getquill"   %% "quill-jdbc-zio" % "4.6.0",
    "org.postgresql" % "postgresql"     % "42.5.4",
    "org.flywaydb"   % "flyway-core"    % "9.16.0"
  )

  val telegramium = Seq(
    "io.github.apimorphism" %% "telegramium-core" % versions.telegramium,
    "io.github.apimorphism" %% "telegramium-high" % versions.telegramium,
    "dev.zio"               %% "zio-interop-cats" % "23.0.0.4"
  )

  val googleApiClient = Seq(
    "com.google.api-client" % "google-api-client"           % "2.2.0",
    "com.google.apis"       % "google-api-services-youtube" % "v3-rev20230502-2.0.0"
  )

  val enumeratum = Seq(
    "com.beachape" %% "enumeratum" % versions.enumeratum
  )
}
