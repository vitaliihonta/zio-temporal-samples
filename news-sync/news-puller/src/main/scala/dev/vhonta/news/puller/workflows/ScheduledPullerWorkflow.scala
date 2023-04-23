package dev.vhonta.news.puller.workflows

import dev.vhonta.news.puller.{InitialPullerState, PullerParameters, PullerTopicState, ResetPuller}
import zio._
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._
import java.time.LocalDateTime
import java.util.UUID

@workflowInterface
trait ScheduledPullerWorkflow {
  @workflowMethod
  def startPulling(initialState: InitialPullerState): Unit

  @signalMethod
  def resetState(command: ResetPuller): Unit

  @signalMethod
  def resetStateAll(): Unit
}

case class PullerWorkflowTopicState(lastProcessedAt: LocalDateTime)

class ScheduledPullerWorkflowImpl extends ScheduledPullerWorkflow {
  private val logger         = ZWorkflow.getLogger(getClass)
  private val state          = ZWorkflowState.make(Map.empty[UUID, PullerWorkflowTopicState])
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

  private val nextRun = ZWorkflow.newContinueAsNewStub[ScheduledPullerWorkflow].build

  override def startPulling(initialState: InitialPullerState): Unit = {
    state := initialState.states.view
      .map(state => state.topicId.fromProto -> PullerWorkflowTopicState(state.lastProcessedAt.fromProto[LocalDateTime]))
      .toMap

    val startedAt = ZWorkflow.currentTimeMillis.toLocalDateTime()

    val topics = ZActivityStub.execute(
      databaseActivities.loadNewsTopics
    )

    val topicPullParameters = topics.topics.view.map { topic =>
      val topicId    = topic.id.fromProto
      val topicState = state.snapshot.get(topicId)

      PullerParameters(
        topicId = topicId,
        topic = topic.topic,
        language = topic.language,
        from = topicState.map(_.lastProcessedAt.toProto),
        to = startedAt
      )
    }.toList

    // remove deleted topics
    locally {
      val existingTopics = topicPullParameters.view.map(_.topicId.fromProto).toSet
      state.update(_.view.filterKeys(existingTopics.contains).toMap)
    }

    val pullTasks = topicPullParameters.map { parameters =>
      val topicId = parameters.topicId.fromProto

      logger.info(
        s"Starting puller topicId=$topicId from=${parameters.from} to=${parameters.to}"
      )

      val pullTopicNewsWorkflow = ZWorkflow
        .newChildWorkflowStub[PullTopicNewsWorkflow]
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
          Some(topicId)
        }
        .catchAll { error =>
          logger.warn(s"Puller topicId=$topicId failed, will retry later", error)
          ZAsync.succeed(None)
        }
    }

    // Wait until all completed
    ZAsync.collectAllDiscard(pullTasks).run

    // Update puller states
    pullTasks.foreach { pull =>
      pull.run.getOrThrow match {
        case None => ()
        case Some(topicId) =>
          state.update(_.updated(topicId, PullerWorkflowTopicState(lastProcessedAt = startedAt)))
      }
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
    state.update(_ - topicId)
  }

  override def resetStateAll(): Unit = {
    logger.info("Resetting ALL puller state")
    state := Map.empty
  }
}
