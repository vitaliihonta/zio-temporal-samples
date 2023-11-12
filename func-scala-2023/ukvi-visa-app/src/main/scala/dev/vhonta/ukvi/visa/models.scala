package dev.vhonta.ukvi.visa

import java.time.{LocalDate, LocalDateTime}
import enumeratum.{Enum, EnumEntry}
import zio.json._
import EnumeratumZioJson._

sealed abstract class ApplicationStep(val order: Int) extends EnumEntry.Snakecase

object ApplicationStep extends Enum[ApplicationStep] {
  case object ConfirmEmail         extends ApplicationStep(0)
  case object ProvidePrimaryInfo   extends ApplicationStep(1)
  case object ProvideTravelHistory extends ApplicationStep(2)
  case object UploadDocuments      extends ApplicationStep(3)
  case object PayServiceFee        extends ApplicationStep(4)
  case object FinishSubmission     extends ApplicationStep(5)
  case object Scoring              extends ApplicationStep(6)
  case object FinalDecision        extends ApplicationStep(7)
  case object PassDelivery         extends ApplicationStep(8)
  case object Finished             extends ApplicationStep(9)

  override val values = findValues

  implicit val ordering: Ordering[ApplicationStep] = Ordering.by((_: ApplicationStep).order)
}

@jsonMemberNames(SnakeCase)
case class PrimaryInfo(
  firstName:   String,
  lastName:    String,
  birthDate:   LocalDate,
  citizenship: String)

object PrimaryInfo {
  implicit val jsonCodec: JsonCodec[PrimaryInfo] = DeriveJsonCodec.gen[PrimaryInfo]
}

@jsonMemberNames(SnakeCase)
case class TravelRecord(
  country:  String,
  fromDate: LocalDate,
  toDate:   LocalDate)

object TravelRecord {
  implicit val jsonCodec: JsonCodec[TravelRecord] = DeriveJsonCodec.gen[TravelRecord]
}

sealed trait DocumentType extends EnumEntry.Snakecase
object DocumentType extends Enum[DocumentType] {
  case object Passport        extends DocumentType
  case object ResidencyPermit extends DocumentType
  case object EvidenceOfFunds extends DocumentType

  override val values = findValues
}

@jsonMemberNames(SnakeCase)
case class UploadedDocument(
  documentType: DocumentType,
  documentId:   String)

object UploadedDocument {
  implicit val jsonCodec: JsonCodec[UploadedDocument] = DeriveJsonCodec.gen[UploadedDocument]
}

@jsonMemberNames(SnakeCase)
case class Biometrics(
  photoId:        String,
  fingerprintsId: String)

object Biometrics {
  implicit val jsonCodec: JsonCodec[Biometrics] = DeriveJsonCodec.gen[Biometrics]
}

@jsonMemberNames(SnakeCase)
case class SubmissionData(
  deliveryAddress: String,
  phoneNumber:     String,
  biometrics:      Biometrics)

object SubmissionData {
  implicit val jsonCodec: JsonCodec[SubmissionData] = DeriveJsonCodec.gen[SubmissionData]
}

@jsonMemberNames(SnakeCase)
case class ApplicationFormView(
  email:              String,
  createdAt:          LocalDateTime,
  modifiedAt:         LocalDateTime,
  nextStep:           ApplicationStep,
  cancelled:          Boolean,
  cancellationReason: Option[String],
  info:               Option[PrimaryInfo],
  travelHistory:      Option[List[TravelRecord]],
  documents:          Option[List[UploadedDocument]],
  payed:              Option[Boolean],
  submissionData:     Option[SubmissionData],
  score:              Option[Int],
  approved:           Option[Boolean])

object ApplicationFormView {
  implicit val jsonCodec: JsonCodec[ApplicationFormView] = DeriveJsonCodec.gen[ApplicationFormView]
}
