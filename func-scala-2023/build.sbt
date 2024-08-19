ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.14"
ThisBuild / resolvers ++= Resolver.sonatypeOssRepos("snapshots")

lazy val root = (project in file("."))
  .settings(
    name := "func-scala-2023"
  )
  .aggregate(
    `ukvi-visa-app`,
    `deployments-app`
  )

lazy val sharedDeps = {
  val zioTemporalVersion = "0.6.1+4-374feefe-SNAPSHOT"
  val zioVersion         = "2.1.7"
  val zioLoggingVersion  = "2.3.0"
  val zioConfigVersion   = "4.0.2"

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
    "com.beachape" %% "enumeratum" % "1.7.4",
    "io.scalaland" %% "chimney"    % "1.4.0",
    // todo: embed into zio-temporal
    "com.github.pjfanning" %% "jackson-module-enumeratum" % "2.17.1"
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

lazy val `deployments-app` = project
  .in(file("deployments-app"))
  .settings(
    name := "deployments-app",
    libraryDependencies ++= sharedDeps ++ Seq(
      "dev.zio" %% "zio-json" % "0.5.0"
    )
  )
