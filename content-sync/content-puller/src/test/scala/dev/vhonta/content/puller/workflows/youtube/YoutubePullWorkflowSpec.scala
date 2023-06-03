package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.puller.proto.YoutubePullerParameters
import dev.vhonta.content.puller.workflows.DatabaseActivities
import zio._
import zio.logging.backend.SLF4J
import zio.test._
import zio.temporal._
import zio.temporal.protobuf.ProtobufDataConverter
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.temporal.testkit._

object YoutubePullWorkflowSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override val spec = suite("YoutubePullWorkflow")(
    test("pulls everything") {
      val videosCount = 5

      val testCase = for {
        uuid <- ZIO.randomWith(_.nextUUID)
        taskQueue = s"youtube-$uuid"

        _ <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[YoutubePullWorkflowImpl].fromClass @@
               ZWorker.addActivityImplementationService[YoutubeActivities] @@
               ZWorker.addActivityImplementationService[DatabaseActivities]

        _ <- ZTestWorkflowEnvironment.setup()

        youtubeWorkflow <- ZTestWorkflowEnvironment
                             .newWorkflowStub[YoutubePullWorkflow]
                             .withTaskQueue(taskQueue)
                             .withWorkflowId(s"youtube/$uuid")
                             .build

        result <- ZWorkflowStub.execute(
                    youtubeWorkflow.pull(
                      YoutubePullerParameters(integrationId = 1, minDate = 0, maxResults = 10)
                    )
                  )
      } yield {
        assertTrue(result.processed == videosCount)
      }

      testCase.provideSome[ZTestWorkflowEnvironment[Any] with Scope](
        MockDatabaseActivities.make,
        MockYoutubeActivities.make(videosCount),
        // todo: backport layer func?
        ZLayer.fromZIO(ZTestWorkflowEnvironment.activityOptions[Any])
      )
    }
  ).provideSome[Scope](
    ZTestWorkflowEnvironment.make[Any],
    ZTestEnvironmentOptions.make,
    ZWorkerFactoryOptions.make,
    ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withDataConverter(ProtobufDataConverter.makeAutoLoad())
  )
}
