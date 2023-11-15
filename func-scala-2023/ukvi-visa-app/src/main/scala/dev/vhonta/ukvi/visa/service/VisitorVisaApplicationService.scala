package dev.vhonta.ukvi.visa.service

import dev.vhonta.ukvi.visa.{ApplicantQuestionnaireData, ApplicationFormView, ApplicationInput}
import dev.vhonta.ukvi.visa.workflow._
import zio._
import zio.temporal.workflow._

import java.util.UUID

object VisitorVisaApplicationService {
  val make: URLayer[ZWorkflowClient, VisitorVisaApplicationService] =
    ZLayer.derive[VisitorVisaApplicationService]
}

class VisitorVisaApplicationService(workflowClient: ZWorkflowClient) {

  /** @return
    *   application ID
    */
  def createApplication(email: String): Task[UUID] = {
    for {
      applicationId <- Random.nextUUID
      visaApplicationWorkflow <- workflowClient.newWorkflowStub[VisitorVisaApplicationWorkflow](
                                   ZWorkflowOptions
                                     .withWorkflowId(s"visitor/${applicationId}")
                                     .withTaskQueue(TaskQueues.Main)
                                 )
      _ <- ZWorkflowStub.start(
             visaApplicationWorkflow.processApplication(
               ApplicationInput(email)
             )
           )
      // For the sake of simplicity...
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.confirmEmail()
             )
             .delay(2.seconds)
    } yield applicationId
  }

  def getApplication(id: UUID): Task[ApplicationFormView] =
    getExistingStub(id).flatMap(getApplicationState)

  def proceedApplicationQuestionnaire(id: UUID, step: ApplicantQuestionnaireData): Task[Unit] = {
    getExistingStub(id).flatMap { visaApplicationWorkflow =>
      ZWorkflowStub.signal(
        visaApplicationWorkflow.proceedQuestionnaire(step)
      )
    }
  }

  private def getExistingStub(applicationId: UUID): UIO[ZWorkflowStub.Of[VisitorVisaApplicationWorkflow]] =
    workflowClient.newWorkflowStub[VisitorVisaApplicationWorkflow](s"visitor/${applicationId}")

  private def getApplicationState(
    visaApplicationWorkflow: ZWorkflowStub.Of[VisitorVisaApplicationWorkflow]
  ): Task[ApplicationFormView] =
    ZWorkflowStub
      .query(
        visaApplicationWorkflow.getApplicationState
      )
      .map(_.toView)
}
