package dev.vhonta.ukvi.visa

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.github.pjfanning.enumeratum.EnumeratumModule
import zio._
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J
import zio.temporal.worker._
import zio.temporal.activity._
import zio.temporal.json.{BoxedUnitModule, JacksonDataConverter}
import zio.temporal.workflow._
import java.time.LocalDate
import java.util.UUID

object Main extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val activitiesLayer = ZLayer.collectAll(
      List(
        ZActivityImplementationObject.layer(ConfigurationActivities.make)
      )
    )

    val registerWorker = ZWorkerFactory.newWorker(TaskQueues.Main) @@
      ZWorker.addWorkflow[VisitorVisaApplicationWorkflowImpl].fromClass @@
      ZWorker.addActivityImplementationsLayer(activitiesLayer)

    // todo: remove
    val simulation = for {
      visaApplicationWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient] { client =>
                                   client.newWorkflowStub[VisitorVisaApplicationWorkflow](
                                     ZWorkflowOptions
                                       .withWorkflowId("vitalii-test")
                                       .withTaskQueue(TaskQueues.Main)
                                   )
                                 }
      _ <- ZWorkflowStub.start(
             visaApplicationWorkflow.processApplication(
               ApplicationInput(email = "example@vhonta.dev")
             )
           )
      _ <- ZIO.sleep(5.seconds)
      statusPolling <- ZWorkflowStub
                         .query(
                           visaApplicationWorkflow.getApplicationState
                         )
                         .tap(state => ZIO.logInfo(s"Current application state is $state"))
                         .repeat(Schedule.fixed(4.seconds))
                         .unit
                         .fork

      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.confirmEmail()
             )
             .delay(5.seconds)

      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.proceedQuestionnaire(
                 AddApplicantPrimaryInfo(
                   PrimaryInfo(
                     firstName = "Taras",
                     secondName = "Shevchenko",
                     birthDate = LocalDate.of(1991, 8, 24),
                     citizenship = "Ukraine"
                   )
                 )
               )
             )
             .delay(5.seconds)

      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.proceedQuestionnaire(
                 AddApplicantTravelRecord(
                   TravelRecord(
                     country = "Poland",
                     fromDate = LocalDate.of(2012, 7, 1),
                     toDate = LocalDate.of(2012, 7, 14)
                   )
                 )
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.proceedQuestionnaire(
                 AddApplicantTravelRecord(
                   TravelRecord(
                     country = "Spain",
                     fromDate = LocalDate.of(2012, 8, 1),
                     toDate = LocalDate.of(2012, 8, 7)
                   )
                 )
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.proceedQuestionnaire(
                 ApplicantTravelRecordingFinished()
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.uploadDocuments(
                 UploadDocuments(
                   List(
                     UploadedDocument(
                       documentType = DocumentType.Passport,
                       documentId = s"pass/ua/${UUID.randomUUID()}"
                     ),
                     UploadedDocument(
                       documentType = DocumentType.EvidenceOfFunds,
                       documentId = s"funds/ua/${UUID.randomUUID()}"
                     )
                   )
                 )
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.serviceFeePayed()
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.addSubmissionData(
                 AddSubmissionData(
                   SubmissionData(
                     deliveryAddress = "Kyiv, Academika Yanhela Street, 20",
                     phoneNumber = "+380671234567",
                     biometrics = Biometrics(
                       photoId = s"photos/${UUID.randomUUID()}",
                       fingerprintsId = s"fingerprints/${UUID.randomUUID()}"
                     )
                   )
                 )
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.addScore(
                 AddScore(90)
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.makeFinalDecision(
                 MakeFinalDecision(true)
               )
             )
             .delay(5.seconds)
      _ <- ZWorkflowStub
             .signal(
               visaApplicationWorkflow.markAsDelivered()
             )
             .delay(5.seconds)
      _           <- statusPolling.interrupt
      finalResult <- visaApplicationWorkflow.result[ApplicationResult]
      _           <- ZIO.logInfo(s"Final result=${finalResult}")
    } yield ()

    val program = for {
      _ <- ZIO.logInfo("Starting UKVI Visas App!")
      _ <- registerWorker
      _ <- ZWorkflowServiceStubs.setup()
      _ <- ZWorkerFactory.setup
      _ <- ZIO.logInfo("Workers started!")
      _ <- simulation
    } yield ()

    program
      .provideSome[Scope](
        // temporal
        ZWorkflowClient.make,
        ZActivityRunOptions.default,
        ZWorkflowServiceStubs.make,
        ZWorkerFactory.make,
        // options
        ZWorkflowServiceStubsOptions.make,
        ZWorkflowClientOptions.make @@
          ZWorkflowClientOptions.withDataConverter(
            JacksonDataConverter.make(
              JsonMapper
                .builder()
                .addModule(DefaultScalaModule)
                .addModule(new JavaTimeModule)
                .addModule(BoxedUnitModule)
                .addModule(EnumeratumModule)
                .build()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            )
          ),
        ZWorkerFactoryOptions.make
      )
      .withConfigProvider(
        ConfigProvider.defaultProvider orElse
          TypesafeConfigProvider.fromResourcePath()
      )
  }
}
