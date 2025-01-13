package dev.vhonta.ukvi.visa

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.github.pjfanning.enumeratum.EnumeratumModule
import dev.vhonta.ukvi.visa.api.{AbstractApi, HomepageApi, VisitorVisaApplicantAdminApi, VisitorVisaApplicantApi}
import dev.vhonta.ukvi.visa.service.VisitorVisaApplicationService
import dev.vhonta.ukvi.visa.workflow.{ConfigurationActivities, TaskQueues, VisitorVisaApplicationWorkflowImpl}
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.http.Server
import zio.logging.backend.SLF4J
import zio.temporal.activity._
import zio.temporal.json.JacksonDataConverter
import zio.temporal.worker._
import zio.temporal.workflow._

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val activitiesLayer = ZLayer.collectAll(
      List(
        ZActivityImplementationObject.layer(ConfigurationActivities.make)
      )
    )

    val registerWorker = ZWorkerFactory.newWorker(TaskQueues.Main) @@
      ZWorker.addWorkflow[VisitorVisaApplicationWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationsLayer(activitiesLayer)

    val program = for {
      _ <- ZIO.logInfo("Starting UKVI Visas App!")
      _ <- registerWorker
      _ <- ZWorkflowServiceStubs.setup()
      _ <- ZWorkerFactory.setup
      _ <- ZIO.logInfo("Workers started!")
      _ <- ZIO.serviceWithZIO[List[AbstractApi]] { apis =>
             val routes = apis.map(_.routes).reduce(_ ++ _)
             Server.serve(routes.toHttpApp)
           }
    } yield ()

    program
      .provideSome[Scope](
        // API
        ZLayer.collectAll(
          List(
            VisitorVisaApplicantApi.make,
            HomepageApi.make,
            VisitorVisaApplicantAdminApi.make
          )
        ),
        Server.configured(NonEmptyChunk("server")),
        // Service layer
        VisitorVisaApplicationService.make,
        // temporal
        ZWorkflowClient.make,
        ZActivityRunOptions.default,
        ZWorkflowServiceStubs.make,
        ZWorkerFactory.make,
        // options
        ZWorkflowServiceStubsOptions.make,
        ZWorkflowClientOptions.make @@
          ZWorkflowClientOptions.withDataConverter(
            JacksonDataConverter.make(
              JacksonDataConverter.DefaultObjectMapperBuilder
                .addModule(EnumeratumModule)
                .build()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            )
          ),
        ZWorkerFactoryOptions.make
      )
      .withConfigProvider(
        ConfigProvider.defaultProvider orElse
          TypesafeConfigProvider.fromResourcePath()
      )
  }
}
