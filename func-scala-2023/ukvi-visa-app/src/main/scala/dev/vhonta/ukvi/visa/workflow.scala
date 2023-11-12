package dev.vhonta.ukvi.visa

import zio._
import zio.temporal._
import zio.temporal.activity.{ZActivityOptions, ZActivityStub}
import zio.temporal.workflow._
import zio.temporal.state._
import scala.Ordering.Implicits._
import scala.util.control.Breaks._

@workflowInterface
trait VisitorVisaApplicationWorkflow {
  @workflowMethod
  def processApplication(input: ApplicationInput): ApplicationResult

  @queryMethod
  def getApplicationState: ApplicationResult

  @signalMethod
  def confirmEmail(): Unit

  @signalMethod
  def cancelApplication(): Unit

  @signalMethod
  def proceedQuestionnaire(data: ApplicantQuestionnaireData): Unit

  @signalMethod
  def uploadDocuments(documents: UploadDocuments): Unit

  @signalMethod
  def serviceFeePayed(): Unit

  @signalMethod
  def addSubmissionData(data: AddSubmissionData): Unit

  @signalMethod
  def addScore(score: AddScore): Unit

  @signalMethod
  def makeFinalDecision(decision: MakeFinalDecision): Unit

  @signalMethod
  def markAsDelivered(): Unit
}

class VisitorVisaApplicationWorkflowImpl extends VisitorVisaApplicationWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val configurationActivities = ZWorkflow.newActivityStub[ConfigurationActivities](
    ZActivityOptions
      .withStartToCloseTimeout(5.seconds)
      .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(5))
  )

  private val state = ZWorkflowState.empty[ApplicationResult]
  override def processApplication(input: ApplicationInput): ApplicationResult = {
    state := ApplicationResult.withEmail(input.email, ZWorkflow.currentTimeMillis.toLocalDateTime())

    val timeouts = ZActivityStub.execute(
      configurationActivities.loadApplicationTimeouts()
    )

    logger.info(s"Waiting ${timeouts.emailConfirmationTimeout} for the email confirmation...")
    val confirmed = ZWorkflow.awaitUntil(timeouts.emailConfirmationTimeout)(
      state.exists(s => s.cancelled || s.nextStep == ApplicationStep.ProvidePrimaryInfo)
    )

    // confirmation timeout or cancel received
    if (!confirmed) {
      return state.update(_.cancel("Email not confirmed", ZWorkflow.currentTimeMillis.toLocalDateTime())).snapshot
    }

    logger.info(s"Setting global timeout=${timeouts.globalFormTimeout}")
    // set the global timeout for the application
    val globalTimeoutWatchDog = ZWorkflow.newCancellationScope {
      ZWorkflow
        .newTimer(timeouts.globalFormTimeout)
        .map { _ =>
          logger.info("Form timed out")
          state.update(_.cancel("Form timeout", ZWorkflow.currentTimeMillis.toLocalDateTime()))
        }
    }

    // executes the timer
    globalTimeoutWatchDog.run()

    breakable {
      for (
        nextStep <- List(
                      ApplicationStep.ProvideTravelHistory,
                      ApplicationStep.UploadDocuments,
                      ApplicationStep.PayServiceFee,
                      ApplicationStep.FinishSubmission,
                      ApplicationStep.Scoring
                    )
      ) {
        logger.info(s"Waiting for step=${state.snapshot.nextStep.entryName}...")
        ZWorkflow.awaitUntil(
          state.exists(s => s.cancelled || s.nextStep == nextStep)
        )
        // global timeout or cancel received
        if (state.exists(_.cancelled)) {
          break()
        }
      }
    }

    if (state.exists(_.cancelled)) {
      return state.snapshot
    }
    // No timeout after the final submission
    globalTimeoutWatchDog.cancel()

    breakable {
      for (
        nextStep <- List(
                      ApplicationStep.FinalDecision,
                      // no sense to wait for delivery step as there is nothing to do there
                      ApplicationStep.Finished
                    )
      ) {
        logger.info(s"Waiting for step=${state.snapshotOf(_.nextStep.entryName)}...")
        ZWorkflow.awaitUntil(
          state.exists(s => s.cancelled || s.nextStep == nextStep)
        )
        state.snapshotOf(_.nextStep) match {
          case ApplicationStep.FinalDecision =>
            // Automatically reject if the score is too low
            if (state.exists(_.score.exists(_ < 75))) {
              state.update(_.withFinalDecision(false, ZWorkflow.currentTimeMillis.toLocalDateTime())).snapshot
              break()
            }
          case _ =>
        }
      }
    }

    // The end...
    state.snapshot
  }

  override def getApplicationState: ApplicationResult = {
    state.snapshot
  }

  override def cancelApplication(): Unit = {
    logger.info("Cancelling the application")
    state.update(_.cancel("User cancelled", ZWorkflow.currentTimeMillis.toLocalDateTime()))
  }

  @signalMethod
  override def confirmEmail(): Unit = {
    logger.info("Email confirmed")
    state.update(_.withEmailConfirmed(ZWorkflow.currentTimeMillis.toLocalDateTime()))
  }

  override def proceedQuestionnaire(data: ApplicantQuestionnaireData): Unit = {
    state.updateWhen {
      case state if state.nextStep <= ApplicationStep.UploadDocuments =>
        data match {
          case AddApplicantPrimaryInfo(info) =>
            logger.info("Updating primary info")
            state.withPrimaryInfo(info, ZWorkflow.currentTimeMillis.toLocalDateTime())
          case AddApplicantTravelRecord(record) =>
            logger.info("Adding travel record")
            state.addTravelRecord(record, ZWorkflow.currentTimeMillis.toLocalDateTime())
          case ApplicantTravelRecordingFinished() =>
            logger.info("Travel history provided")
            state.withTravelHistoryRecorded(ZWorkflow.currentTimeMillis.toLocalDateTime())
        }
    }
  }

  override def uploadDocuments(documents: UploadDocuments): Unit = {
    state.updateWhen {
      case state if state.nextStep == ApplicationStep.UploadDocuments =>
        logger.info("Uploaded documents")
        state.withDocuments(documents.values, ZWorkflow.currentTimeMillis.toLocalDateTime())
    }
  }

  @signalMethod
  override def serviceFeePayed(): Unit = {
    state.updateWhen {
      case state if state.nextStep == ApplicationStep.PayServiceFee =>
        logger.info("Service fee payed")
        state.withServiceFeePayed(ZWorkflow.currentTimeMillis.toLocalDateTime())
    }
  }

  override def addSubmissionData(data: AddSubmissionData): Unit = {
    state.updateWhen {
      case state if state.nextStep == ApplicationStep.FinishSubmission =>
        logger.info("Submission finished!")
        state.withSubmission(data.value, ZWorkflow.currentTimeMillis.toLocalDateTime())
    }
  }

  override def addScore(score: AddScore): Unit = {
    state.updateWhen {
      case state if state.nextStep == ApplicationStep.Scoring =>
        logger.info("Application scored")
        state.withScore(score.value, ZWorkflow.currentTimeMillis.toLocalDateTime())
    }
  }

  override def makeFinalDecision(decision: MakeFinalDecision): Unit = {
    state.updateWhen {
      case state if state.nextStep == ApplicationStep.FinalDecision =>
        logger.info("Decision made about the application")
        state.withFinalDecision(decision.approved, ZWorkflow.currentTimeMillis.toLocalDateTime())
    }
  }

  @signalMethod
  override def markAsDelivered(): Unit = {
    state.updateWhen {
      case state if state.nextStep == ApplicationStep.PassDelivery =>
        logger.info("Pass delivered")
        state.finish(ZWorkflow.currentTimeMillis.toLocalDateTime())
    }
  }
}
