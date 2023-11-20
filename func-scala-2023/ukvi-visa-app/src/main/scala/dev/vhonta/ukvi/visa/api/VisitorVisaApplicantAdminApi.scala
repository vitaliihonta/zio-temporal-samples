package dev.vhonta.ukvi.visa.api

import dev.vhonta.ukvi.visa.service.VisitorVisaApplicationService
import dev.vhonta.ukvi.visa.{ApplicationStep, FinalDecisionRequest}
import zio._
import zio.http._
import zio.json.{uuid => _, _}

import java.util.UUID

object VisitorVisaApplicantAdminApi {
  val make: URLayer[VisitorVisaApplicationService, VisitorVisaApplicantAdminApi] =
    ZLayer.derive[VisitorVisaApplicantAdminApi]
}

class VisitorVisaApplicantAdminApi(
  visitorVisaApplicationService: VisitorVisaApplicationService)
    extends AbstractApi {
  override val routes: Routes[Any, Response] = {
    Routes[Any, Throwable](
      Method.GET / "api" / "v1" / "admin" / "visitor" / "application" / uuid("application-id") -> handler {
        (_: UUID, _: Request) =>
          Handler.fromResource("static/admin/application.html")
      }.flatten,
      // View
      Method.GET / "api" / "v1" / "admin" / "visitor" / "application" / uuid("application-id") / "view" -> handler {
        (applicationId: UUID, _: Request) =>
          visitorVisaApplicationService
            .getApplication(applicationId)
            .map(application => Response.json(application.toJson))
      },
      // Next verification step
      Method.GET / "api" / "v1" / "admin" / "visitor" / "application" / uuid(
        "application-id"
      ) / "nextVerificationStep" -> handler { (applicationId: UUID, _: Request) =>
        visitorVisaApplicationService
          .getApplication(applicationId)
          .map { application =>
            application.nextStep match {
              case ApplicationStep.FinalDecision =>
                hxRedirect(
                  (URL.root / "api" / "v1" / "admin" / "visitor" / "application" / applicationId.toString / "final-decision").addTrailingSlash
                )
              case _ => Response.notImplemented
            }
          }
      },
      // Scoring
      Method.GET / "api" / "v1" / "admin" / "visitor" / "application" / uuid(
        "application-id"
      ) / "final-decision" -> handler { (_: UUID, _: Request) =>
        Handler.fromResource("static/admin/scoring.html")
      }.flatten,
      Method.POST / "api" / "v1" / "admin" / "visitor" / "application" / uuid(
        "application-id"
      ) / "final-decision" -> handler { (applicationId: UUID, request: Request) =>
        for {
          body          <- request.body.asString
          finalDecision <- ZIO.fromEither(body.fromJson[FinalDecisionRequest]).mapError(new IllegalArgumentException(_))
          _ <- visitorVisaApplicationService.makeFinalDecision(
                 applicationId,
                 finalDecision.isApproved
               )
        } yield {
          hxRedirect(
            (URL.root / "api" / "v1" / "admin" / "visitor" / "application" / applicationId.toString).addTrailingSlash
          )
        }
      }
    )
      .handleError(Response.fromThrowable)
  }
}
