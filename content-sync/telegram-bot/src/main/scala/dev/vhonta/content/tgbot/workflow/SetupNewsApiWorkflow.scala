package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.tgbot.bot.ContentSyncCommand.ListIntegrations
import dev.vhonta.content.tgbot.proto._
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._

@workflowInterface
trait SetupNewsApiWorkflow {
  @workflowMethod
  def setup(params: SetupNewsApiParams): SetupResult

  @signalMethod
  def provideApiKey(setupNewsApi: SetupNewsApi): Unit

  @queryMethod
  def currentStep(): CurrentSetupNewsApiStep
}

object SetupNewsApiWorkflowImpl {
  private sealed abstract class SetupState(val step: SetupNewsApiStep)
  private object SetupState {
    case object WaitingForApiKey             extends SetupState(SetupNewsApiStep.WaitingForApiKey)
    case class ValidatingKey(apiKey: String) extends SetupState(SetupNewsApiStep.ValidatingKey)
    case object FailedKeyValidation          extends SetupState(SetupNewsApiStep.FailedKeyValidation)
    case object StoringKey                   extends SetupState(SetupNewsApiStep.StoringKey)
  }
}

class SetupNewsApiWorkflowImpl extends SetupNewsApiWorkflow {
  import SetupNewsApiWorkflowImpl._

  private val logger = ZWorkflow.makeLogger
  private val newsApiActivities = ZWorkflow
    .newActivityStub[NewsApiActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(3)
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

  private val state = ZWorkflowState.make[SetupState](SetupState.WaitingForApiKey)

  override def setup(params: SetupNewsApiParams): SetupResult = {
    logger.info("Waiting for the apikey...")
    val apiKeySet = ZWorkflow.awaitUntil(12.hours)(state.exists(_.step == SetupNewsApiStep.ValidatingKey))
    if (!apiKeySet) {
      notifyUserIgnoreError(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = s"Have you forgotten about News API integration?"
        )
      )
      state := SetupState.FailedKeyValidation
      SetupResult(SetupResult.Value.FailureReason("timeout"))
    } else {
      val SetupState.ValidatingKey(apiKey) = state.snapshot
      logger.info("Going to test the key")
      val validationResult = ZActivityStub.execute(
        newsApiActivities.testApiKey(
          TestApiKeyParams(apiKey = apiKey)
        )
      )
      if (!validationResult.valid) {
        notifyUserIgnoreError(
          NotifySubscriberParams(
            subscriber = params.subscriber,
            message = s"The API key is invalid. Please ensure you provided a correct one"
          )
        )
        state := SetupState.FailedKeyValidation
        SetupResult(SetupResult.Value.FailureReason("Invalid api key"))
      } else {
        state := SetupState.StoringKey
        logger.info("Storing the apiKey...")
        val storeResult = ZActivityStub.execute(
          newsApiActivities.storeNewsApiIntegration(
            StoreNewsApiIntegrationParams(
              subscriber = params.subscriber,
              apiKey = apiKey
            )
          )
        )
        logger.info("Integration created successfully!")
        notifyUserIgnoreError(
          NotifySubscriberParams(
            subscriber = params.subscriber,
            message =
              s"Successfully created the News API integration!\nCheck available integrations with /${ListIntegrations.entryName}"
          )
        )
        SetupResult(
          SetupResult.Value.Integration(
            storeResult.integration
          )
        )
      }
    }
  }

  override def provideApiKey(setupNewsApi: SetupNewsApi): Unit = {
    logger.info("Received apiKey!")
    state := SetupState.ValidatingKey(setupNewsApi.apiKey)
  }

  override def currentStep(): CurrentSetupNewsApiStep = {
    CurrentSetupNewsApiStep(
      value = state.snapshotOf(_.step)
    )
  }

  private def notifyUserIgnoreError(params: NotifySubscriberParams): Unit = {
    ZActivityStub
      .executeAsync(
        telegramActivities.notifySubscriber(params)
      )
      .run
  }
}
