package dev.vhonta.ukvi.visa.api

import dev.vhonta.ukvi.visa._
import dev.vhonta.ukvi.visa.service.VisitorVisaApplicationService
import zio._
import zio.http._
import zio.json.{uuid => _, _}

import java.util.UUID

object VisitorVisaApplicantApi {
  val make: URLayer[VisitorVisaApplicationService, VisitorVisaApplicantApi] =
    ZLayer.derive[VisitorVisaApplicantApi]
}

class VisitorVisaApplicantApi(
  visitorVisaApplicationService: VisitorVisaApplicationService)
    extends AbstractApi {

  override val routes: Routes[Any, Response] = {
    Routes[Any, Throwable](
      // Initialize
      Method.POST / "api" / "v1" / "visitor" / "init" -> handler { (request: Request) =>
        for {
          form <- request.body.asURLEncodedForm
          emailField <- ZIO
                          .succeed(form.get("email"))
                          .someOrFail(
                            new IllegalArgumentException("Email is missing")
                          )
          email         <- emailField.asText
          applicationId <- visitorVisaApplicationService.createApplication(email)
        } yield hxRedirect(
          (URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString).addTrailingSlash
        )
      },
      Method.GET / "api" / "v1" / "visitor" / "application" / uuid("application-id") -> handler {
        (_: UUID, _: Request) =>
          Handler.fromResource("static/application.html")
      }.flatten,
      // View
      Method.GET / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "view" -> handler {
        (applicationId: UUID, _: Request) =>
          visitorVisaApplicationService
            .getApplication(applicationId)
            .map(application => Response.json(application.toJson))
      },
      // Next
      Method.GET / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "next" -> handler {
        (applicationId: UUID, _: Request) =>
          visitorVisaApplicationService
            .getApplication(applicationId)
            .map { application =>
              application.nextStep match {
                case ApplicationStep.ProvidePrimaryInfo =>
                  hxRedirect(
                    (URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString / "primary-info").addTrailingSlash
                  )
                case ApplicationStep.ProvideTravelHistory =>
                  hxRedirect(
                    (URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString / "travel-history").addTrailingSlash
                  )
                case _ => Response.notImplemented
              }
            }
      },
      // Primary info
      Method.GET / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "primary-info" -> handler {
        (_: UUID, _: Request) =>
          Handler.fromResource("static/primary-info.html")
      }.flatten,
      Method.POST / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "primary-info" -> handler {
        (applicationId: UUID, request: Request) =>
          for {
            body <- request.body.asString
            info <- ZIO.fromEither(body.fromJson[PrimaryInfo]).mapError(new IllegalArgumentException(_))
            _ <- visitorVisaApplicationService.proceedApplicationQuestionnaire(
                   applicationId,
                   AddApplicantPrimaryInfo(info)
                 )
          } yield {
            hxRedirect((URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString).addTrailingSlash)
          }
      },
      // Travel history
      Method.GET / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "travel-history" -> handler {
        (_: UUID, _: Request) =>
          Handler.fromResource("static/travel-history.html")
      }.flatten,
      Method.PATCH / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "travel-history" -> handler {
        (applicationId: UUID, request: Request) =>
          for {
            body <- request.body.asString
            info <- ZIO.fromEither(body.fromJson[TravelRecord]).mapError(new IllegalArgumentException(_))
            _ <- visitorVisaApplicationService.proceedApplicationQuestionnaire(
                   applicationId,
                   AddApplicantTravelRecord(info)
                 )
          } yield {
            hxRedirect(
              (URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString / "travel-history").addTrailingSlash
            )
          }
      },
      Method.PUT / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "travel-history" -> handler {
        (applicationId: UUID, _: Request) =>
          for {
            _ <- visitorVisaApplicationService.proceedApplicationQuestionnaire(
                   applicationId,
                   ApplicantTravelRecordingFinished()
                 )
            // Uploading documents for the sake of simplicity
            _ <- visitorVisaApplicationService.uploadDocuments(
                   applicationId,
                   List(
                     UploadedDocument(DocumentType.Passport, s"Pass-$applicationId"),
                     UploadedDocument(DocumentType.EvidenceOfFunds, s"Pass-$applicationId")
                   )
                 )
            // Marking fees as paid for the sake of simplicity
            _ <- visitorVisaApplicationService.markServiceFeePaid(applicationId)
            // Adding submission data for the sake of simplicity
            _ <- visitorVisaApplicationService.addSubmissionData(
                   applicationId,
                   SubmissionData(
                     deliveryAddress = "Academika Yanhela 20, Kyiv, 03056",
                     phoneNumber = "+380971234567",
                     biometrics = Biometrics(
                       photoId = s"Photo-$applicationId",
                       fingerprintsId = s"Fingerprint-$applicationId"
                     )
                   )
                 )
          } yield {
            hxRedirect((URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString).addTrailingSlash)
          }
      }
    ).handleError(Response.fromThrowable)
  }

}
