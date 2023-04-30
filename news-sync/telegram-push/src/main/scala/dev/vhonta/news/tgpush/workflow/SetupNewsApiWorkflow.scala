package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.tgpush.bot.NewsSyncCommand.ListIntegrations
import dev.vhonta.news.tgpush.proto._
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._

@workflowInterface
trait SetupNewsApiWorkflow {
  @workflowMethod
  def setup(params: SetupParams): SetupResult

  @signalMethod
  def provideApiKey(setupNewsApi: SetupNewsApi): Unit

  @queryMethod
  def currentStep(): CurrentSetupStep
}

object SetupNewsApiWorkflowImpl {
  private sealed abstract class SetupState(val step: SetupStep)
  private object SetupState {
    case object WaitingForApiKey             extends SetupState(SetupStep.WaitingForApiKey)
    case class ValidatingKey(apiKey: String) extends SetupState(SetupStep.ValidatingKey)
    case object StoringKey                   extends SetupState(SetupStep.StoringKey)
  }
}

class SetupNewsApiWorkflowImpl extends SetupNewsApiWorkflow {
  import SetupNewsApiWorkflowImpl._

  private val logger = ZWorkflow.getLogger(getClass)
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
          nameOf[ReaderNotFoundException]
        )
    )
    .build

  private val state = ZWorkflowState.make[SetupState](SetupState.WaitingForApiKey)

  override def setup(params: SetupParams): SetupResult = {
    logger.info("Waiting for the apikey...")
    val apiKeySet = ZWorkflow.awaitUntil(12.hours)(state.exists(_.step == SetupStep.ValidatingKey))
    if (!apiKeySet) {
      notifyUserIgnoreError(
        NotifyReaderParams(
          reader = params.reader,
          message = s"Have you forgotten about News API integration?"
        )
      )
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
          NotifyReaderParams(
            reader = params.reader,
            message = s"The API key is invalid. Please ensure you provided a correct one"
          )
        )
        SetupResult(SetupResult.Value.FailureReason("Invalid api key"))
      } else {
        state := SetupState.StoringKey
        logger.info("Storing the apiKey...")
        val storeResult = ZActivityStub.execute(
          newsApiActivities.storeIntegration(
            StoreNewsApiIntegrationParams(
              reader = params.reader,
              apiKey = apiKey
            )
          )
        )
        logger.info("Integration created successfully!")
        notifyUserIgnoreError(
          NotifyReaderParams(
            reader = params.reader,
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

  override def currentStep(): CurrentSetupStep = {
    CurrentSetupStep(
      value = state.snapshotOf(_.step)
    )
  }

  private def notifyUserIgnoreError(params: NotifyReaderParams): Unit = {
    ZActivityStub
      .executeAsync(
        telegramActivities.notifyReader(params)
      )
      .run
  }
}
