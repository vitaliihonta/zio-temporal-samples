import sbt._

object Dependencies {
  private object versions {
    val zioTemporal = "0.2.0"
    val zio         = "2.0.12"
    val zioLogging  = "2.1.12"
    val zioConfig   = "4.0.0-RC14"
    val zioJson     = "0.5.0"
    val enumeratum  = "1.7.2"
    val sttp        = "3.8.15"
    val telegramium = "7.67.0"
    val spark       = "3.4.1"
    val quill       = "4.6.0"
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

  val zio = Seq(
    "dev.zio"       %% "zio"                 % versions.zio,
    "dev.zio"       %% "zio-logging"         % versions.zioLogging,
    "dev.zio"       %% "zio-logging-slf4j2"  % versions.zioLogging,
    "dev.zio"       %% "zio-streams"         % versions.zio,
    "dev.zio"       %% "zio-config"          % versions.zioConfig,
    "dev.zio"       %% "zio-config-typesafe" % versions.zioConfig,
    "dev.zio"       %% "zio-test"            % versions.zio % Test,
    "dev.zio"       %% "zio-test-sbt"        % versions.zio % Test,
    "ch.qos.logback" % "logback-classic"     % "1.4.8"
  )

  val zioNio = Seq(
    "dev.zio" %% "zio-nio" % "2.0.1"
  )

  val json = Seq(
    "dev.zio" %% "zio-json" % versions.zioJson
  )

  val parquet = Seq(
    // No way to write parquet without hadoop dependency
    "org.apache.hadoop"         % "hadoop-client"  % "3.3.6" exclude ("org.slf4j", "slf4j-reload4j"),
    "com.github.mjakubowski84" %% "parquet4s-core" % "2.11.0"
  )

  private val sparkExclusions = Vector(
    ExclusionRule(organization = "org.slf4j", name = "jcl-over-slf4j"),
    ExclusionRule(organization = "org.apache.logging.log4j", name = "log4j-1.2-api")
  )

  val sparkLauncher = Seq(
    "org.apache.spark" %% "spark-launcher" % versions.spark withExclusions sparkExclusions
  )

  val sparkJob = Seq(
    "org.apache.spark" %% "spark-sql" % versions.spark % Provided withExclusions sparkExclusions
  )

  val cmd = Seq(
    "com.github.scopt" %% "scopt" % "4.1.0"
  )

  val zioHttp = Seq(
    "dev.zio" %% "zio-http" % "3.0.0-RC1"
  )

  val mockito = Seq(
    "org.mockito" %% "mockito-scala" % "1.17.14" % Test
  )

  val zioTestFrameworks = Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

  private val postgres = "org.postgresql" % "postgresql" % "42.5.4"

  val sparkDatabase = Seq(
    "io.getquill" %% "quill-jdbc" % versions.quill,
    postgres
  )

  val zioQuill = Seq(
    "io.getquill" %% "quill-jdbc-zio" % versions.quill,
    "org.flywaydb" % "flyway-core"    % "9.16.0",
    postgres
  )

  val scalaLogging = Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.4"
  )

  val telegramium = Seq(
    "io.github.apimorphism" %% "telegramium-core" % versions.telegramium,
    "io.github.apimorphism" %% "telegramium-high" % versions.telegramium exclude ("org.slf4j", "slf4j-simple"),
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
