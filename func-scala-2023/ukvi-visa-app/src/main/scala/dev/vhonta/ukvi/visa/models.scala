package dev.vhonta.ukvi.visa

import java.time.LocalDate
import enumeratum.{Enum, EnumEntry}

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

case class PrimaryInfo(
  firstName:   String,
  secondName:  String,
  birthDate:   LocalDate,
  citizenship: String)

case class TravelRecord(
  country:  String,
  fromDate: LocalDate,
  toDate:   LocalDate)

sealed trait DocumentType extends EnumEntry.Snakecase
object DocumentType extends Enum[DocumentType] {
  case object Passport        extends DocumentType
  case object ResidencyPermit extends DocumentType
  case object EvidenceOfFunds extends DocumentType

  override val values = findValues
}

case class UploadedDocument(
  documentType: DocumentType,
  documentId:   String)

case class Biometrics(
  photoId:        String,
  fingerprintsId: String)

case class SubmissionData(
  deliveryAddress: String,
  phoneNumber:     String,
  biometrics:      Biometrics)
