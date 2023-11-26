package dev.vhonta.deployments.manager

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.github.pjfanning.enumeratum.EnumeratumModule
import zio._
import zio.logging.backend.SLF4J
import zio.temporal.worker._
import zio.temporal.activity._
import zio.temporal.json.{BoxedUnitModule, JacksonDataConverter}
import zio.temporal.workflow._

import java.net.URL

object Main extends ZIOAppDefault {
  val TaskQueue = "DeploymentManagement"

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val activitiesLayer = ZLayer.collectAll(
      List(
        ZActivityImplementationObject.layer(DeploymentActivitiesImpl.make)
      )
    )

    val registerWorker = ZWorkerFactory.newWorker(TaskQueue) @@
      ZWorker.addWorkflow[DeploymentWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationsLayer(activitiesLayer)

    val startWorkflow = ZIO.serviceWithZIO[ZWorkflowClient] { client =>
      for {
        workflowId <- Random.nextUUID
        deploymentWorkflow <- client.newWorkflowStub[DeploymentWorkflow](
                                ZWorkflowOptions
                                  .withWorkflowId(workflowId.toString)
                                  .withTaskQueue(TaskQueue)
                              )
        result <- ZWorkflowStub.execute(
                    deploymentWorkflow.deploy(
                      DeploymentParams(
                        // NOTE: remove "bad" word to the service name (lowercase) to make the deployment succeed
                        List(
                          ServiceDeploymentRequest(
                            name = "companies-service",
                            aspects = List(ServiceAspect.HttpApi(address = new URL("https://companies-service")))
                          ),
                          ServiceDeploymentRequest(
                            name = "streaming-service-bad",
                            aspects = List(
                              ServiceAspect.HttpApi(address = new URL("https://streaming-service")),
                              ServiceAspect.KafkaConsumer(
                                topic = "companies-cdf",
                                consumerGroup = "streaming-service"
                              )
                            )
                          )
                        )
                      )
                    )
                  )
        _ <- ZIO.logInfo(s"Deployment result=$result")
      } yield ()
    }

    val program = for {
      _ <- ZIO.logInfo("Starting Deployment app!")
      _ <- registerWorker
      _ <- ZWorkflowServiceStubs.setup()
      _ <- ZWorkerFactory.setup
      _ <- ZIO.logInfo("Workers started!")
      _ <- startWorkflow
    } yield ()

    program
      .provideSome[Scope](
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
              JsonMapper
                .builder()
                .addModule(DefaultScalaModule)
                .addModule(new JavaTimeModule)
                .addModule(BoxedUnitModule)
                .addModule(EnumeratumModule)
                .build()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            )
          ),
        ZWorkerFactoryOptions.make
      )
  }
}
