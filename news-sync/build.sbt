ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.10"

val baseProtoSettings = Seq(
  Compile / PB.targets := Seq(
    scalapb.gen(
      flatPackage = true,
      grpc = false
    ) -> (Compile / sourceManaged).value / "scalapb"
  ),
  // mac m1 workaround
  PB.protocDependency :=
    ("com.google.protobuf" % "protoc" % PB.protocVersion.value).artifacts(
      Artifact(
        name = "protoc",
        `type` = PB.ProtocBinary,
        extension = "exe",
        classifier = "osx-x86_64"
      )
    )
)

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
    baseProtoSettings,
    libraryDependencies ++= Dependencies.enumeratum
  )

lazy val `news-puller` = project
  .in(file("news-puller"))
  .dependsOn(shared)
  .settings(
    baseProtoSettings,
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
    baseProtoSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal
  )

lazy val `telegram-push` = project
  .in(file("telegram-push"))
  .dependsOn(shared)
  .settings(
    baseProtoSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.telegramium ++
        Dependencies.circe
  )
