package dev.vhonta.ukvi.visa

import zio._
import zio.http._
import zio.json.{uuid => _, _}
import zio.temporal.workflow._
import java.util.UUID

class api {}

object VisitorVisaApi {
  val make: URLayer[ZWorkflowClient, VisitorVisaApi] =
    ZLayer.derive[VisitorVisaApi]
}

// TODO: refactor
class VisitorVisaApi(workflowClient: ZWorkflowClient) {

  val routes: Routes[Any, Response] = {
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
          for {
            visaApplicationWorkflow <- getExistingStub(applicationId)
            status                  <- getApplicationState(visaApplicationWorkflow)
          } yield {
            Response.json(status.toJson)
          }
      },
      // Next
      Method.GET / "api" / "v1" / "visitor" / "application" / uuid("application-id") / "next" -> handler {
        (applicationId: UUID, _: Request) =>
          for {
            visaApplicationWorkflow <- getExistingStub(applicationId)
            status                  <- getApplicationState(visaApplicationWorkflow)
          } yield {
            status.nextStep match {
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
            visaApplicationWorkflow <- getExistingStub(applicationId)
            body                    <- request.body.asString
            info <- ZIO.fromEither(body.fromJson[PrimaryInfo]).mapError(new IllegalArgumentException(_))
            _ <- ZWorkflowStub.signal(
                   visaApplicationWorkflow.proceedQuestionnaire(
                     AddApplicantPrimaryInfo(info)
                   )
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
            visaApplicationWorkflow <- getExistingStub(applicationId)
            body                    <- request.body.asString
            info <- ZIO.fromEither(body.fromJson[TravelRecord]).mapError(new IllegalArgumentException(_))
            _ <- ZWorkflowStub.signal(
                   visaApplicationWorkflow.proceedQuestionnaire(
                     AddApplicantTravelRecord(info)
                   )
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
            visaApplicationWorkflow <- getExistingStub(applicationId)
            _ <- ZWorkflowStub.signal(
                   visaApplicationWorkflow.proceedQuestionnaire(
                     ApplicantTravelRecordingFinished()
                   )
                 )
          } yield {
            hxRedirect((URL.root / "api" / "v1" / "visitor" / "application" / applicationId.toString).addTrailingSlash)
          }
      },
      // Assets
      Method.GET / "assets" / trailing -> handler { (path: Path, _: Request) =>
        Handler.fromResource(s"static/assets/${path.segments.mkString("/")}")
      }.flatten,
      RoutePattern.GET ->
        Handler.fromResource("static/index.html")
    ).handleError(Response.fromThrowable)
  }

  private def hxRedirect(to: URL): Response =
    Response(
      status = Status.Ok,
      headers = Headers(
        Header.Custom("HX-Redirect", to.encode)
      )
    )

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
