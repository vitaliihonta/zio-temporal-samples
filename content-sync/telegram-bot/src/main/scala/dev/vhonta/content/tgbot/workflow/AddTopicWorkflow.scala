package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.tgbot.proto._
import dev.vhonta.content.tgbot.workflow.common.{ContentFeedActivities, SubscriberNotFoundException, TelegramActivities}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._

@workflowInterface
trait AddTopicWorkflow {
  @workflowMethod
  def add(params: AddTopicParams): Unit

  @signalMethod
  def specifyTopic(setupNewsApi: SpecifyTopic): Unit

  @queryMethod
  def currentStep(): CurrentAddTopicStep
}

object AddTopicWorkflowImpl {
  private sealed abstract class AddTopicState(val step: AddTopicStep)
  private object AddTopicState {
    case object WaitingForTopic            extends AddTopicState(AddTopicStep.WaitingForTopic)
    case class StoringTopic(topic: String) extends AddTopicState(AddTopicStep.StoringTopic)
  }
}

class AddTopicWorkflowImpl extends AddTopicWorkflow {
  import AddTopicWorkflowImpl._

  private val logger = ZWorkflow.makeLogger
  private val newsFeedActivities = ZWorkflow
    .newActivityStub[ContentFeedActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(3)
    )
    .build

  private val telegramActivities = ZWorkflow
    .newActivityStub[TelegramActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        .withDoNotRetry(nameOf[SubscriberNotFoundException])
    )
    .build

  private val state = ZWorkflowState.make[AddTopicState](AddTopicState.WaitingForTopic)

  override def add(params: AddTopicParams): Unit = {
    logger.info("Waiting for the topic...")
    val topicSet = ZWorkflow.awaitUntil(12.hours)(state.exists(_.step == AddTopicStep.StoringTopic))
    if (!topicSet) {
      notifyUserIgnoreError(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = s"Have you forgotten about new topic sync?"
        )
      )
    } else {
      val AddTopicState.StoringTopic(topic) = state.snapshot
      ZActivityStub.execute(
        newsFeedActivities.createTopic(
          CreateTopicParams(
            subscriber = params.subscriber,
            topic = topic
          )
        )
      )
      notifyUserIgnoreError(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = s"Topic <b>$topic</b> successfully created! Wait for news feed next evening 🙌",
          parseMode = Some(TelegramParseMode.Html)
        )
      )
    }
  }

  override def specifyTopic(topic: SpecifyTopic): Unit = {
    logger.info(s"Specified topic=${topic.value}")
    state := AddTopicState.StoringTopic(topic.value)
  }

  override def currentStep(): CurrentAddTopicStep = {
    CurrentAddTopicStep(value = state.snapshotOf(_.step))
  }

  private def notifyUserIgnoreError(params: NotifySubscriberParams): Unit = {
    ZActivityStub
      .executeAsync(
        telegramActivities.notifySubscriber(params)
      )
      .run
  }
}
