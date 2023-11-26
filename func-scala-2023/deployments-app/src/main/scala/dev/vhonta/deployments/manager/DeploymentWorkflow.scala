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
    // Initialize the state
    deploymentState := input.deployments.view.map { req =>
      req.id -> DeploymentProgressItem(
        id = req.id,
        status = ServiceDeploymentStatus.Planned,
        failure = None
      )
    }.toMap

    val deploymentSaga: ZSaga[Unit] = ZSaga
      .foreach(input.deployments) { req =>
        // Update deployment status
        deploymentState.update(
          req.id,
          DeploymentProgressItem(
            id = req.id,
            status = ServiceDeploymentStatus.InProgress,
            failure = None
          )
        )

        for {
          // Step 1: deploy
          deployment <- ZSaga.attempt(
                          ZActivityStub
                            .execute(
                              deploymentActivities.deployService(
                                DeployServiceParams(req.id)
                              )
                            )
                        )
          // Step 2: add rollback action
          _ <- ZSaga.compensation {
                 deploymentState.update(
                   req.id,
                   DeploymentProgressItem(
                     id = req.id,
                     status = ServiceDeploymentStatus.RollingBack,
                     failure = deploymentState.get(req.id).flatMap(_.failure)
                   )
                 )
                 ZActivityStub.execute(
                   deploymentActivities.rollbackService(req.id)
                 )
               }
          // Step 3: monitor traffic
          _ <- monitorTraffic(req, deployment)
        } yield ()
      }
      .unit

    val status: DeploymentResultStatus = deploymentSaga.run() match {
      case Right(_) => DeploymentResultStatus.Completed
      case Left(_)  => DeploymentResultStatus.Failed
    }

    DeploymentResult(
      status = status,
      deployments = deploymentState.snapshot.values.map { deployment =>
        DeploymentResultItem(
          name = deployment.id,
          failure = deployment.failure
        )
      }.toList
    )
  }

  override def getProgress: DeploymentProgress =
    DeploymentProgress(
      deploymentState.snapshot.values.toList
    )

  private def monitorTraffic(
    req:        ServiceDeploymentRequest,
    deployment: DeployServiceResult
  ): ZSaga[Unit] =
    ZSaga.attempt {
      var elapsed = 0.seconds
      // Monitoring period should be bigger in real life
      while (elapsed <= 30.seconds) {
        val startedAt = ZWorkflow.currentTimeMillis.toInstant
        // wait for checks
        performCheck(req, deployment).run.getOrThrow
        // make pauses
        ZWorkflow.sleep(5.seconds)
        // track elapsed time
        val checkedAt = ZWorkflow.currentTimeMillis.toInstant
        elapsed += Duration.fromInterval(startedAt, checkedAt)
      }

      // consider deployment successful
      deploymentState.update(
        req.id,
        DeploymentProgressItem(
          id = req.id,
          status = ServiceDeploymentStatus.Completed,
          failure = None
        )
      )
    }

  private def performCheck(
    req:        ServiceDeploymentRequest,
    deployment: DeployServiceResult
  ): ZAsync[Unit] = {
    ZAsync
      .foreachParDiscard(req.aspects) { aspect =>
        val checkAsync = aspect match {
          case ServiceAspect.HttpApi(hostname) =>
            ZActivityStub
              .executeAsync(
                deploymentActivities.httpTrafficStats(
                  req.id,
                  HttpTrafficStatsParams(hostname)
                )
              )
          case ServiceAspect.KafkaConsumer(topic, consumerGroup) =>
            ZActivityStub
              .executeAsync(
                deploymentActivities.kafkaTrafficStats(
                  req.id,
                  KafkaTrafficStatsParams(topic, consumerGroup)
                )
              )
        }

        checkAsync.flatMap { stats =>
          stats.error match {
            case None => ZAsync.unit
            case Some(error) =>
              val failure = s"${stats.statsType} failed for service=${req.id} reason=$error"
              deploymentState.update(
                req.id,
                DeploymentProgressItem(
                  id = req.id,
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
  }
}
