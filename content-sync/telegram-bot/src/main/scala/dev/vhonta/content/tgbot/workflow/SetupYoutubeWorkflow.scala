package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.ContentFeedIntegrationDetails
import dev.vhonta.content.tgbot.proto._
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._

@workflowInterface
trait SetupYoutubeWorkflow {
  @workflowMethod
  def setup(params: SetupYoutubeParams): SetupResult

  @signalMethod
  def provideCallbackData(callbackData: YoutubeCallbackData): Unit

  @queryMethod
  def currentStep(): CurrentSetupYoutubeStep
}

object SetupYoutubeWorkflowImpl {
  private sealed abstract class SetupState(val step: SetupYoutubeStep)
  private object SetupState {
    case object Initial                                      extends SetupState(SetupYoutubeStep.InitiatingYoutubeOauth)
    case object WaitingForCallback                           extends SetupState(SetupYoutubeStep.WaitingForCallback)
    case class GettingCredentials(authorizationCode: String) extends SetupState(SetupYoutubeStep.GettingCredentials)
    case class Testing(
      tokens: ContentFeedIntegrationDetails.Youtube)
        extends SetupState(SetupYoutubeStep.TestingYoutubeCredentials)
    case object StoringTokens extends SetupState(SetupYoutubeStep.StoringTokens)
    case object Failed        extends SetupState(SetupYoutubeStep.FailedYoutubeSetup)
  }
}

class SetupYoutubeWorkflowImpl extends SetupYoutubeWorkflow {
  import SetupYoutubeWorkflowImpl._

  private val logger = ZWorkflow.makeLogger

  private val youtubeActivities = ZWorkflow
    .newActivityStub[YoutubeActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(5)
    )
    .build

  private val telegramActivities = ZWorkflow
    .newActivityStub[TelegramActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        .withDoNotRetry(
          nameOf[SubscriberNotFoundException]
        )
    )
    .build

  private val state = ZWorkflowState.make[SetupState](SetupState.Initial)

  // TODO: implement
  override def setup(params: SetupYoutubeParams): SetupResult = ???

  override def provideCallbackData(callbackData: YoutubeCallbackData): Unit = {
    logger.info("Received callback data!")
    state := SetupState.GettingCredentials(callbackData.authorizationCode)
  }

  override def currentStep(): CurrentSetupYoutubeStep = {
    CurrentSetupYoutubeStep(
      value = state.snapshotOf(_.step)
    )
  }
}
