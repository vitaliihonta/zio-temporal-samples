ThisBuild / version      := "0.2.1"
ThisBuild / scalaVersion := "2.13.10"
ThisBuild / organization := "dev.vhonta"

val baseSettings = Seq(
  Compile / PB.targets := Seq(
    scalapb.gen(
      flatPackage = true,
      grpc = false
    ) -> (Compile / sourceManaged).value / "scalapb"
  ),
  // mac m1 workaround
  PB.protocDependency := {
    if (System.getProperty("os.arch") == "aarch64" && System.getProperty("os.name") == "Mac OS X") {
      ("com.google.protobuf" % "protoc" % PB.protocVersion.value).artifacts(
        Artifact(
          name = "protoc",
          `type` = PB.ProtocBinary,
          extension = "exe",
          classifier = "osx-x86_64"
        )
      )
    } else PB.protocDependency.value
  },
  testFrameworks ++= Dependencies.zioTestFrameworks
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
    publish / skip      := true,
    publishLocal / skip := true,
    name                := "content-sync-root"
  )
  .aggregate(
    shared,
    `content-puller`,
    `content-processor`,
    `telegram-bot`
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
        Dependencies.googleApiClient ++
        Dependencies.enumeratum
  )

lazy val `content-puller` = project
  .in(file("content-puller"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.mockito
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val `content-processor` = project
  .in(file("content-processor"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val `telegram-bot` = project
  .in(file("telegram-bot"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioBase ++
        Dependencies.zioTemporal ++
        Dependencies.telegramium ++
        Dependencies.zioHttp
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
