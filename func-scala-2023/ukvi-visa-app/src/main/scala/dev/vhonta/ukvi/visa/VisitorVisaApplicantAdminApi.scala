package dev.vhonta.ukvi.visa

import zio._
import zio.json.{uuid => _, _}
import zio.http._
import dev.vhonta.ukvi.visa.api.AbstractApi
import dev.vhonta.ukvi.visa.service.VisitorVisaApplicationService
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
            // todo: implement
            application.nextStep match {
              case ApplicationStep.Scoring =>
                hxRedirect(
                  (URL.root / "api" / "v1" / "admin" / "visitor" / "application" / applicationId.toString / "scoring").addTrailingSlash
                )
              case ApplicationStep.FinalDecision =>
                hxRedirect(
                  (URL.root / "api" / "v1" / "admin" / "visitor" / "application" / applicationId.toString / "final-decision").addTrailingSlash
                )
              case _ => Response.notImplemented
            }
          }
      }
    )
      .handleError(Response.fromThrowable)
  }
}
