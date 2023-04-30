ThisBuild / version      := "0.1.0"
ThisBuild / scalaVersion := "2.13.10"
ThisBuild / organization := "dev.vhonta"

val baseSettings = Seq(
  Compile / PB.targets := Seq(
    scalapb.gen(
      flatPackage = true,
      grpc = false
    ) -> (Compile / sourceManaged).value / "scalapb"
  ),
  PB.protocDependency := {
    System.getProperty("os.arch") match {
      case "aarch64" =>
        // mac m1 workaround
        ("com.google.protobuf" % "protoc" % PB.protocVersion.value).artifacts(
          Artifact(
            name = "protoc",
            `type` = PB.ProtocBinary,
            extension = "exe",
            classifier = "osx-x86_64"
          )
        )

      case _ => PB.protocDependency.value
    }
  }
)

val baseServiceSettings = Seq(
  dockerBaseImage           := "eclipse-temurin:11",
  (Docker / dockerUsername) := Some("vhonta"),
  (Docker / packageName)    := name.value
)

lazy val root = project
  .in(file("."))
  .settings(
    baseSettings,
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
    baseSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.database ++
        Dependencies.sttp ++
        Dependencies.circe ++
        Dependencies.enumeratum
  )

lazy val `news-puller` = project
  .in(file("news-puller"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.circe
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val `news-processor` = project
  .in(file("news-processor"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val `telegram-push` = project
  .in(file("telegram-push"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.telegramium ++
        Dependencies.circe
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
