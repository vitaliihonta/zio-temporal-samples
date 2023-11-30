package dev.vhonta.ukvi.visa.service

import dev.vhonta.ukvi.visa.{
  AddScore,
  AddSubmissionData,
  ApplicantQuestionnaireData,
  ApplicationFormView,
  ApplicationInput,
  MakeFinalDecision,
  SubmissionData,
  UploadDocuments,
  UploadedDocument
}
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
                                     .withWorkflowId(s"visitor/$applicationId")
                                     .withTaskQueue(TaskQueues.Main)
                                 )
      _ <- ZIO.logInfo(s"Creating visitor visa application id=$applicationId")
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
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Proceeding questionnaire step application=$id step=${step.getClass}")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.proceedQuestionnaire(step)
           )
    } yield ()
  }

  def uploadDocuments(id: UUID, documents: List[UploadedDocument]): Task[Unit] = {
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Uploading documents application=$id")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.uploadDocuments(UploadDocuments(documents))
           )
    } yield ()
  }

  def markServiceFeePaid(id: UUID): Task[Unit] = {
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Marking service fee as paid application=$id")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.serviceFeePaid()
           )
    } yield ()
  }

  def addSubmissionData(id: UUID, data: SubmissionData): Task[Unit] = {
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Adding submission data application=$id")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.addSubmissionData(
               AddSubmissionData(data)
             )
           )
    } yield ()
  }

  def addScore(id: UUID, score: Int): Task[Unit] = {
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Adding score application=$id")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.addScore(
               AddScore(score)
             )
           )
    } yield ()
  }

  def makeFinalDecision(id: UUID, approved: Boolean): Task[Unit] = {
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Making final decision on application=$id")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.makeFinalDecision(
               MakeFinalDecision(approved)
             )
           )
    } yield ()
  }

  def markAsDelivered(id: UUID): Task[Unit] = {
    for {
      visaApplicationWorkflow <- getExistingStub(id)
      _                       <- ZIO.logInfo(s"Delivered application=$id")
      _ <- ZWorkflowStub.signal(
             visaApplicationWorkflow.markAsDelivered()
           )
    } yield ()
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
