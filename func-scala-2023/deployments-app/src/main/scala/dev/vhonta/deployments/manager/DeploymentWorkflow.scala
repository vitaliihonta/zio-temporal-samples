package dev.vhonta.deployments.manager

import zio._
import zio.temporal._
import zio.temporal.workflow._
import zio.temporal.state._
import zio.temporal.activity._

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

  private val deploymentState = ZWorkflowState.empty[DeploymentProgress]
  private var isCancelled     = false

  override def deploy(input: DeploymentParams): DeploymentResult = {
    deploymentState := DeploymentProgress(
      deployments = input.deployments.map { req =>
        DeploymentProgressItem(
          name = req.name,
          traffic = 0,
          status = ServiceDeploymentStatus.Planned,
          failure = None
        )
      }
    )

    val deploymentSaga = ZSaga.foreach(input.deployments) { deploymentRequest =>
      for {
        deployment <- ZSaga.attempt(
                        ZActivityStub
                          .execute(
                            deploymentActivities.deployService(
                              DeployServiceParams(deploymentRequest.name)
                            )
                          )
                      )
        _ <- ZSaga.compensation(
               ZActivityStub.execute(
                 deploymentActivities.rollbackService(deployment.serviceDeploymentId)
               )
             )
        // TODO: monitor traffic
      } yield ()
    }

    // TODO: finish
    deploymentSaga.unit.run() match {
      case Right(_)    => ???
      case Left(error) => ???
    }
  }

  override def getProgress: DeploymentProgress =
    deploymentState.snapshot
}
