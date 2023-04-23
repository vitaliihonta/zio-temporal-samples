ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.10"

lazy val root = project
  .in(file("."))
  .settings(
    name := "news-sync-root"
  )
  .aggregate(
    shared,
    `news-puller`,
    `news-processor`,
    `telegram-push`
  )

lazy val shared = project
  .in(file("shared"))
  .settings(
    libraryDependencies ++= Dependencies.enumeratum
  )

lazy val `news-puller` = project
  .in(file("news-puller"))
  .dependsOn(shared)
  .settings(
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.sttp ++
        Dependencies.circe
  )

lazy val `news-processor` = project
  .in(file("news-processor"))
  .dependsOn(shared)
  .settings(
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal
  )

lazy val `telegram-push` = project
  .in(file("telegram-push"))
  .dependsOn(shared)
  .settings(
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.telegramium ++
        Dependencies.circe
  )
