ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.11"

lazy val root = (project in file("."))
  .settings(
    name := "func-scala-2023"
  )
  .aggregate(
    `ukvi-visa-app`,
    `data-ingestion-app`
  )

lazy val sharedDeps = {
  val zioTemporalVersion = "0.6.0"
  val zioVersion         = "2.0.19"
  val zioLoggingVersion  = "2.1.14"
  val zioConfigVersion   = "4.0.0-RC16"

  Seq(
    // zio-temporal
    "dev.vhonta" %% "zio-temporal-core"    % zioTemporalVersion,
    "dev.vhonta" %% "zio-temporal-testkit" % zioTemporalVersion % Test,
    // zio
    "dev.zio" %% "zio"         % zioVersion,
    "dev.zio" %% "zio-streams" % zioVersion,
    // configs
    "dev.zio" %% "zio-config"          % zioConfigVersion,
    "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
    // logging
    "dev.zio"       %% "zio-logging"        % zioLoggingVersion,
    "dev.zio"       %% "zio-logging-slf4j2" % zioLoggingVersion,
    "ch.qos.logback" % "logback-classic"    % "1.4.8",
    // utils
    "com.beachape" %% "enumeratum" % "1.7.3",
    "io.scalaland" %% "chimney"    % "0.8.2",
    // todo: embed into zio-temporal
    "com.github.pjfanning" %% "jackson-module-enumeratum" % "2.14.1"
  )
}

lazy val `ukvi-visa-app` = project
  .in(file("ukvi-visa-app"))
  .settings(
    name := "ukvi-visa-app",
    libraryDependencies ++= sharedDeps ++ Seq(
      "dev.zio" %% "zio-http" % "3.0.0-RC3"
    )
  )

lazy val `data-ingestion-app` = project
  .in(file("data-ingestion-app"))
  .settings(
    name := "data-ingestion-app",
    libraryDependencies ++= sharedDeps ++ Seq(
      "dev.zio" %% "zio-http" % "3.0.0-RC3"
    )
  )
