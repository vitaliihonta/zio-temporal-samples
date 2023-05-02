package dev.vhonta.news.puller.workflows

import dev.vhonta.news.proto.NewsFeedIntegrationType
import dev.vhonta.news.puller.proto.{
  InitialPullerState,
  ListIntegrations,
  ListTopics,
  NewsPullerParameters,
  PullerTopicState,
  ResetPuller
}
import zio._
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._
import java.time.LocalDateTime
import java.util.UUID

@workflowInterface
trait NewsApiScheduledPullerWorkflow {
  @workflowMethod(name = "NewsApiPullScheduler")
  def startPulling(initialState: InitialPullerState): Unit

  @signalMethod
  def resetState(command: ResetPuller): Unit

  @signalMethod
  def resetStateAll(): Unit
}

case class NewsApiPullerWorkflowTopicState(lastProcessedAt: LocalDateTime)

class NewsApiScheduledPullerWorkflowImpl extends NewsApiScheduledPullerWorkflow {
  private val logger         = ZWorkflow.makeLogger
  private val state          = ZWorkflowState.emptyMap[UUID, NewsApiPullerWorkflowTopicState]
  private val thisWorkflowId = ZWorkflow.info.workflowId

  // TODO: make configurable
  private val pullInterval      = 15.minutes
  private val singlePullTimeout = 10.minutes

  private val databaseActivities = ZWorkflow
    .newActivityStub[DatabaseActivities]
    .withStartToCloseTimeout(3.minutes)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(3)
    )
    .build

  private val nextRun = ZWorkflow.newContinueAsNewStub[NewsApiScheduledPullerWorkflow].build

  override def startPulling(initialState: InitialPullerState): Unit = {
    state := initialState.states.view
      .map(state =>
        state.topicId.fromProto -> NewsApiPullerWorkflowTopicState(state.lastProcessedAt.fromProto[LocalDateTime])
      )
      .toMap

    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val newsApiIntegrations = ZActivityStub.execute(
      databaseActivities.loadIntegrations(
        ListIntegrations(NewsFeedIntegrationType.news_api)
      )
    )

    logger.info(s"Loaded ${newsApiIntegrations.integrations.size} news-api integrations")

    val integrationDetailsByReader = newsApiIntegrations.integrations.view.flatMap { integration =>
      integration.integration.newsApi.map(integration.readerId -> _)
    }.toMap

    val readers = integrationDetailsByReader.keys.toList

    val topics = ZActivityStub.execute(
      databaseActivities.loadNewsTopics(
        ListTopics(readers)
      )
    )

    logger.info(s"Starting ${topics.topics.size} pulls...")

    val topicPullParameters = topics.topics.view.map { topic =>
      val topicId    = topic.id.fromProto
      val topicState = state.get(topicId)

      NewsPullerParameters(
        apiKey = integrationDetailsByReader(topic.owner).token,
        topicId = topicId,
        topic = topic.topic,
        language = topic.lang,
        from = topicState.map(_.lastProcessedAt.toProto),
        to = startedAt
      )
    }.toList

    // remove deleted topics
    locally {
      val existingTopics = topicPullParameters.view.map(_.topicId.fromProto).toSet
      state.filterKeysInPlace(existingTopics.contains)
    }

    val pullTasks = ZAsync.foreachPar(topicPullParameters) { parameters =>
      val topicId = parameters.topicId.fromProto

      logger.info(
        s"Starting puller topicId=$topicId from=${parameters.from} to=${parameters.to}"
      )

      val pullTopicNewsWorkflow = ZWorkflow
        .newChildWorkflowStub[NewsApiPullTopicNewsWorkflow]
        .withWorkflowId(s"$thisWorkflowId/topics/$topicId")
        // Limit the pull time
        .withWorkflowExecutionTimeout(singlePullTimeout)
        .build

      ZChildWorkflowStub
        .executeAsync(
          pullTopicNewsWorkflow.pull(parameters)
        )
        .map { result =>
          logger.info(s"Puller topicId=$topicId processed ${result.processed} records")
          topicId
        }
        .option
    }

    // Wait until all completed and update puller state
    pullTasks.run.getOrThrow.flatMap(_.toList).foreach { topicId =>
      state.update(topicId, NewsApiPullerWorkflowTopicState(lastProcessedAt = startedAt))
    }

    val finishedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()
    val sleepTime  = pullInterval minus java.time.Duration.between(startedAt, finishedAt)

    logger.info(s"Next pull starts after $sleepTime")

    // Wait for the next run
    ZWorkflow.sleep(sleepTime)

    // Continue as new workflow
    ZWorkflowContinueAsNewStub.execute(
      nextRun.startPulling(
        InitialPullerState(
          states = state.snapshot.view.map { case (topicId, topicState) =>
            PullerTopicState(topicId, topicState.lastProcessedAt)
          }.toList
        )
      )
    )
  }

  override def resetState(command: ResetPuller): Unit = {
    val topicId = command.topicId.fromProto
    logger.info(s"Resetting puller state topicId=$topicId")
    state -= topicId
  }

  override def resetStateAll(): Unit = {
    logger.info("Resetting ALL puller state")
    state.clear()
  }
}
