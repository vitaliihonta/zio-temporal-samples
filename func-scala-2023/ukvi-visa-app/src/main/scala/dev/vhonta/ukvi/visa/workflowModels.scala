package dev.vhonta.ukvi.visa

import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import java.time.LocalDateTime
import io.scalaland.chimney.dsl._

case class ApplicationInput(email: String)

object ApplicationResult {
  def withEmail(email: String, now: LocalDateTime): ApplicationResult =
    new ApplicationResult(
      email,
      nextStep = ApplicationStep.ConfirmEmail,
      createdAt = now,
      modifiedAt = now
    )
}

case class ApplicationResult private (
  email:              String,
  createdAt:          LocalDateTime,
  modifiedAt:         LocalDateTime,
  nextStep:           ApplicationStep,
  cancelled:          Boolean = false,
  cancellationReason: Option[String] = None,
  info:               Option[PrimaryInfo] = None,
  travelHistory:      Option[List[TravelRecord]] = None,
  documents:          Option[List[UploadedDocument]] = None,
  payed:              Option[Boolean] = None,
  submissionData:     Option[SubmissionData] = None,
  score:              Option[Int] = None,
  approved:           Option[Boolean] = None) {

  def cancel(reason: String, now: LocalDateTime): ApplicationResult =
    copy(
      cancelled = true,
      cancellationReason = Some(reason),
      nextStep = ApplicationStep.Finished,
      modifiedAt = now
    )

  def withEmailConfirmed(now: LocalDateTime): ApplicationResult =
    copy(nextStep = ApplicationStep.ProvidePrimaryInfo, modifiedAt = now)

  def withPrimaryInfo(value: PrimaryInfo, now: LocalDateTime): ApplicationResult =
    copy(
      info = Some(value),
      nextStep = ApplicationStep.ProvideTravelHistory,
      modifiedAt = now
    )

  def addTravelRecord(record: TravelRecord, now: LocalDateTime): ApplicationResult = {
    val updatedHistory = travelHistory
      .map(record :: _)
      .getOrElse(List(record))

    copy(
      travelHistory = Some(updatedHistory),
      modifiedAt = now
    )
  }

  def withTravelHistoryRecorded(now: LocalDateTime): ApplicationResult = {
    copy(nextStep = ApplicationStep.UploadDocuments, modifiedAt = now)
  }

  def withDocuments(values: List[UploadedDocument], now: LocalDateTime): ApplicationResult = {
    copy(
      documents = Some(values),
      nextStep = ApplicationStep.PayServiceFee,
      modifiedAt = now
    )
  }

  def withServiceFeePayed(now: LocalDateTime): ApplicationResult =
    copy(
      payed = Some(true),
      nextStep = ApplicationStep.FinishSubmission,
      modifiedAt = now
    )

  def withSubmission(value: SubmissionData, now: LocalDateTime): ApplicationResult =
    copy(
      submissionData = Some(value),
      nextStep = ApplicationStep.Scoring,
      modifiedAt = now
    )

  def withScore(value: Int, now: LocalDateTime): ApplicationResult =
    copy(
      score = Some(value),
      nextStep = ApplicationStep.FinalDecision,
      modifiedAt = now
    )

  def withFinalDecision(value: Boolean, now: LocalDateTime): ApplicationResult =
    copy(
      approved = Some(value),
      nextStep = ApplicationStep.PassDelivery,
      modifiedAt = now
    )

  def finish(now: LocalDateTime): ApplicationResult =
    copy(
      nextStep = ApplicationStep.Finished,
      modifiedAt = now
    )

  def toView: ApplicationFormView =
    this.transformInto[ApplicationFormView]
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[AddApplicantPrimaryInfo], name = "add_primary_info"),
    new JsonSubTypes.Type(value = classOf[AddApplicantTravelRecord], name = "add_travel_record"),
    new JsonSubTypes.Type(value = classOf[ApplicantTravelRecordingFinished], name = "travel_recording_finished")
  )
)
sealed trait ApplicantQuestionnaireData

case class AddApplicantPrimaryInfo(
  info: PrimaryInfo)
    extends ApplicantQuestionnaireData

case class AddApplicantTravelRecord(
  record: TravelRecord)
    extends ApplicantQuestionnaireData

case class ApplicantTravelRecordingFinished() extends ApplicantQuestionnaireData

case class UploadDocuments(
  values: List[UploadedDocument])

case class AddSubmissionData(
  value: SubmissionData)

case class AddScore(value: Int)

case class MakeFinalDecision(approved: Boolean)
