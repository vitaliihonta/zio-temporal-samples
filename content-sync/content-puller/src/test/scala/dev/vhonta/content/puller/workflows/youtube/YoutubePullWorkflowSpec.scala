package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.puller.proto.YoutubePullerParameters
import dev.vhonta.content.puller.workflows.youtube.mock.{
  MockDatabaseActivities,
  MockDatalakeActivities,
  MockYoutubeActivities
}
import zio._
import zio.logging.backend.SLF4J
import zio.test._
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.temporal.testkit._

object YoutubePullWorkflowSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override val spec = suite("YoutubePullWorkflow")(
    test("pulls everything") {
      ZTestWorkflowEnvironment.activityRunOptionsWithZIO[Any] { implicit options =>
        for {
          uuid <- ZIO.randomWith(_.nextUUID)
          taskQueue   = s"youtube-$uuid"
          videosCount = 5

          _ <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
                 ZWorker.addWorkflow[YoutubePullWorkflowImpl].fromClass @@
                 ZWorker.addActivityImplementation(MockYoutubeActivities(videosCount)) @@
                 ZWorker.addActivityImplementation(MockDatabaseActivities()) @@
                 ZWorker.addActivityImplementation(MockDatalakeActivities())

          _ <- ZTestWorkflowEnvironment.setup()

          youtubeWorkflow <- ZTestWorkflowEnvironment.newWorkflowStub[YoutubePullWorkflow](
                               ZWorkflowOptions
                                 .withWorkflowId(s"youtube/$uuid")
                                 .withTaskQueue(taskQueue)
                             )

          result <- ZWorkflowStub.execute(
                      youtubeWorkflow.pull(
                        YoutubePullerParameters(
                          integrationId = 1,
                          minDate = 0,
                          maxResults = 10,
                          datalakeOutputDir = "./test/datalake"
                        )
                      )
                    )
        } yield {
          assertTrue(result.processed == videosCount)
        }
      }
    }
  ).provideSome[Scope](TestModule.workflowTestEnv) @@ TestAspect.withLiveClock
}
