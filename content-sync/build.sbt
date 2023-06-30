ThisBuild / version      := "0.4.0"
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
    `service-commons`,
    `content-puller`,
    `content-processor-launcher`,
    `content-processor-job`,
    `telegram-bot`
  )

lazy val shared = project
  .in(file("shared"))
  .settings(
    baseSettings,
    libraryDependencies ++=
      Dependencies.zioEssential ++
        Dependencies.zioTemporal ++
        Dependencies.enumeratum
  )

lazy val `service-commons` = project
  .in(file("service-commons"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    libraryDependencies ++=
      Dependencies.zioEssential ++
        Dependencies.zioExt ++
        Dependencies.zioTemporal ++
        Dependencies.database ++
        Dependencies.sttp ++
        Dependencies.googleApiClient
  )

lazy val `content-puller` = project
  .in(file("content-puller"))
  .dependsOn(`service-commons`)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioEssential ++
        Dependencies.zioExt ++
        Dependencies.zioTemporal ++
        Dependencies.zioConnect ++
        Dependencies.parquet ++
        Dependencies.mockito
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)

lazy val `content-processor-launcher` = project
  .in(file("content-processor-launcher"))
  .dependsOn(`service-commons`)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioEssential ++
        Dependencies.zioExt ++
        Dependencies.zioTemporal ++
        Dependencies.sparkCore,
    buildInfoKeys := Seq[BuildInfoKey](
      version,
      BuildInfoKey.map(`content-processor-job` / assembly / assemblyJarName) { case (_, v) =>
        "contentProcessorJobJarName" -> v
      }
    ),
    buildInfoPackage := "dev.vhonta.content.processor.launcher"
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)

lazy val `content-processor-job` = project
  .in(file("content-processor-job"))
  .dependsOn(shared)
  .settings(
    baseSettings,
    libraryDependencies ++=
      Dependencies.sparkSql,
    assembly / mainClass := Some("dev.vhonta.content.processor.job.Main"),
    assemblyMergeStrategy := {
      case "application.conf" | "reference.conf" => MergeStrategy.concat
      case x =>
        val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )
  .enablePlugins(AssemblyPlugin)

lazy val `telegram-bot` = project
  .in(file("telegram-bot"))
  .dependsOn(`service-commons`)
  .settings(
    baseSettings,
    baseServiceSettings,
    libraryDependencies ++=
      Dependencies.zioEssential ++
        Dependencies.zioExt ++
        Dependencies.zioTemporal ++
        Dependencies.telegramium ++
        Dependencies.zioHttp
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
