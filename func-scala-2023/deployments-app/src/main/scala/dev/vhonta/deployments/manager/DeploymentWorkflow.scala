package dev.vhonta.deployments.manager

import zio._
import zio.temporal._
import zio.temporal.workflow._
import zio.temporal.state._
import zio.temporal.activity._
import zio.temporal.failure.ApplicationFailure

@workflowInterface
trait DeploymentWorkflow {
  @workflowMethod
  def deploy(input: DeploymentParams): DeploymentResult

  @queryMethod
  def getProgress: DeploymentProgress
}

class DeploymentWorkflowImpl extends DeploymentWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val deploymentActivities = ZWorkflow.newActivityStub[DeploymentActivities](
    ZActivityOptions.withStartToCloseTimeout(10.minutes)
  )

  private val deploymentState = ZWorkflowState.emptyMap[String, DeploymentProgressItem]

  override def deploy(input: DeploymentParams): DeploymentResult = {
    val deploymentSaga = ZSaga.foreach(input.deployments) { req =>
      for {
        deployment <- ZSaga.attempt(
                        ZActivityStub
                          .execute(
                            deploymentActivities.deployService(
                              DeployServiceParams(req.name)
                            )
                          )
                      )
        _ <- ZSaga.compensation {
               deploymentState.update(
                 deployment.serviceDeploymentId,
                 DeploymentProgressItem(
                   name = req.name,
                   status = ServiceDeploymentStatus.RollingBack,
                   failure = deploymentState.get(deployment.serviceDeploymentId).flatMap(_.failure)
                 )
               )
               ZActivityStub.execute(
                 deploymentActivities.rollbackService(deployment.serviceDeploymentId)
               )
             }
        // yield it back
      } yield {
        deploymentState.update(
          deployment.serviceDeploymentId,
          DeploymentProgressItem(
            name = req.name,
            status = ServiceDeploymentStatus.InProgress,
            failure = None
          )
        )
        (req, deployment)
      }
    }

    val trafficChecksSaga = deploymentSaga.flatMap(checkTraffic)

    val status: DeploymentResultStatus = trafficChecksSaga.run() match {
      case Right(_) => DeploymentResultStatus.Failed
      case Left(_)  => DeploymentResultStatus.Completed
    }

    DeploymentResult(
      status = status,
      deployments = deploymentState.snapshot.values.map { deployment =>
        DeploymentResultItem(
          name = deployment.name,
          failure = deployment.failure
        )
      }.toList
    )
  }

  override def getProgress: DeploymentProgress =
    DeploymentProgress(
      deploymentState.snapshot.values.toList
    )

  private def checkTraffic(deployments: List[(ServiceDeploymentRequest, DeployServiceResult)]): ZSaga[Unit] =
    ZSaga.attempt {
      var elapsed = 0.seconds
      // Monitoring period should be bigger in real life
      while (elapsed <= 30.seconds) {
        val startedAt = ZWorkflow.currentTimeMillis.toInstant
        val checkAsync = ZAsync.foreachParDiscard(deployments) { case (deploymentRequest, deployment) =>
          logger.info(s"Monitoring traffic for ${deploymentRequest.name} id=${deployment.serviceDeploymentId}...")
          performCheck(deploymentRequest, deployment)
        }
        // wait for checks
        checkAsync.run.getOrThrow
        // make pauses
        ZWorkflow.sleep(5.seconds)
        // track elapsed time
        val checkedAt = ZWorkflow.currentTimeMillis.toInstant
        elapsed += Duration.fromInterval(startedAt, checkedAt)
      }
    }

  private def performCheck(
    req:        ServiceDeploymentRequest,
    deployment: DeployServiceResult
  ): ZAsync[Unit] = {
    ZAsync
      .foreach(req.aspects) { aspect =>
        val checkAsync = aspect match {
          case ServiceAspect.HttpApi(hostname) =>
            ZActivityStub
              .executeAsync(
                deploymentActivities.httpTrafficStats(
                  deployment.serviceDeploymentId,
                  HttpTrafficStatsParams(hostname)
                )
              )
          case ServiceAspect.KafkaConsumer(topic, consumerGroup) =>
            ZActivityStub
              .executeAsync(
                deploymentActivities.kafkaTrafficStats(
                  deployment.serviceDeploymentId,
                  KafkaTrafficStatsParams(topic, consumerGroup)
                )
              )
        }

        checkAsync.flatMap { stats =>
          stats.error match {
            case None => ZAsync.unit
            case Some(error) =>
              val failure = s"${stats.statsType} failed for service=${req.name} reason=$error"
              deploymentState.update(
                deployment.serviceDeploymentId,
                DeploymentProgressItem(
                  name = req.name,
                  status = ServiceDeploymentStatus.Failed,
                  failure = Some(failure)
                )
              )
              ZAsync.fail(
                ApplicationFailure.newFailure(
                  message = failure,
                  failureType = "TRAFFIC_CHECK_FAILURE"
                )
              )
          }
        }
      }
      .unit
  }
}
