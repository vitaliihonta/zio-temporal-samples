package dev.vhonta.content.puller.workflows.youtube

import dev.vhonta.content.proto.ContentFeedIntegrationType
import dev.vhonta.content.puller.proto.PullingResult
import dev.vhonta.content.puller.workflows.youtube.mock.{
  MockPullConfigurationActivities,
  MockDatabaseActivities,
  MockYoutubePullWorkflow
}
import dev.vhonta.content.puller.{PullerConfig, YoutubePullerConfig}
import zio._
import zio.logging.backend.SLF4J
import zio.temporal.failure.ApplicationFailure
import zio.temporal.testkit.ZTestWorkflowEnvironment
import zio.temporal.worker.ZWorker
import zio.temporal.workflow.ZWorkflowStub
import zio.test._
import java.util.concurrent.atomic.AtomicInteger

object YoutubeScheduledPullWorkflow extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  private val pullerConfig = PullerConfig(
    pullInterval = 15.minutes,
    singlePullTimeout = 5.minutes,
    datalakeOutputDir = "./test/datalake"
  )

  private val youtubePullerConfig = YoutubePullerConfig(maxResults = 100)

  private val mockConfigurationActivities =
    MockPullConfigurationActivities(
      ContentFeedIntegrationType.values.view.map(_ -> pullerConfig).toMap,
      youtubePullerConfig
    )

  override val spec = suite("YoutubeScheduledPullerWorkflow")(
    test("pull successfully") {
      for {
        uuid <- ZIO.randomWith(_.nextUUID)
        taskQueue = s"youtube-scheduled-$uuid"

        activityOptions <- ZTestWorkflowEnvironment.activityOptions[Any]

        invocationsCount = new AtomicInteger()
        pullFunc = () => {
                     invocationsCount.incrementAndGet()
                     PullingResult(1)
                   }

        _ <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[YoutubeScheduledPullerWorkflowImpl].fromClass @@
               ZWorker.addWorkflow[YoutubePullWorkflow].from(MockYoutubePullWorkflow(pullFunc)) @@
               ZWorker.addActivityImplementation(MockDatabaseActivities()(activityOptions)) @@
               ZWorker.addActivityImplementation(mockConfigurationActivities)

        _ <- ZTestWorkflowEnvironment.setup()

        youtubeWorkflow <- ZTestWorkflowEnvironment
                             .newWorkflowStub[YoutubeScheduledPullerWorkflow]
                             .withTaskQueue(taskQueue)
                             .withWorkflowId(s"youtube-scheduled/$uuid")
                             .build
        _ <- ZWorkflowStub.execute(
               youtubeWorkflow.pullAll()
             )
      } yield {
        assertTrue(invocationsCount.get() == 1)
      }
    },
    test("survives if pull workflow fails") {
      for {
        uuid <- ZIO.randomWith(_.nextUUID)
        taskQueue = s"youtube-scheduled-$uuid"

        activityOptions <- ZTestWorkflowEnvironment.activityOptions[Any]

        invocationsCount = new AtomicInteger()
        pullFunc = () => {
                     invocationsCount.incrementAndGet()
                     throw ApplicationFailure.newFailure("BOOOM", "fatal")
                   }

        _ <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[YoutubeScheduledPullerWorkflowImpl].fromClass @@
               ZWorker.addWorkflow[YoutubePullWorkflow].from(MockYoutubePullWorkflow(pullFunc)) @@
               ZWorker.addActivityImplementation(MockDatabaseActivities()(activityOptions)) @@
               ZWorker.addActivityImplementation(mockConfigurationActivities)

        _ <- ZTestWorkflowEnvironment.setup()

        youtubeWorkflow <- ZTestWorkflowEnvironment
                             .newWorkflowStub[YoutubeScheduledPullerWorkflow]
                             .withTaskQueue(taskQueue)
                             .withWorkflowId(s"youtube-scheduled/$uuid")
                             .build

        _ <- ZWorkflowStub.execute(
               youtubeWorkflow.pullAll()
             )
      } yield {
        assertTrue(
          invocationsCount.get() == 1
        )
      }
    }
  ).provideSome[Scope](TestModule.workflowTestEnv) @@ TestAspect.withLiveClock
}
