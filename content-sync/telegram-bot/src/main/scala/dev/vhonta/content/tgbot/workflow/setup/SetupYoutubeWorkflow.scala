package dev.vhonta.content.tgbot.workflow.setup

import dev.vhonta.content.proto.ContentFeedIntegrationYoutubeDetails
import dev.vhonta.content.tgbot.bot.ContentSyncCommand.ListIntegrations
import dev.vhonta.content.tgbot.proto._
import dev.vhonta.content.tgbot.workflow.YoutubeActivities
import dev.vhonta.content.tgbot.workflow.common.{SubscriberNotFoundException, TelegramActivities}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._
import java.util.UUID

@workflowInterface
trait SetupYoutubeWorkflow extends BaseSetupWorkflow[SetupYoutubeParams] {

  @signalMethod
  def provideCallbackData(callbackData: YoutubeCallbackData): Unit

  @queryMethod
  def currentStep(): CurrentSetupYoutubeStep
}

object SetupYoutubeWorkflow {
  def workflowId(subscriberId: UUID): String =
    s"setup/youtube/${subscriberId}"
}

object SetupYoutubeWorkflowImpl {

  private sealed abstract class SetupState(val step: SetupYoutubeStep)
  private object SetupState {
    case object Initial                                      extends SetupState(SetupYoutubeStep.InitiatingYoutubeOauth)
    case object WaitingForCallback                           extends SetupState(SetupYoutubeStep.WaitingForCallback)
    case class GettingCredentials(authorizationCode: String) extends SetupState(SetupYoutubeStep.GettingCredentials)
    case class Testing(
      tokens: ContentFeedIntegrationYoutubeDetails)
        extends SetupState(SetupYoutubeStep.TestingYoutubeCredentials)
    case object StoringTokens extends SetupState(SetupYoutubeStep.StoringTokens)
    case object Failed        extends SetupState(SetupYoutubeStep.FailedYoutubeSetup)
  }
}

class SetupYoutubeWorkflowImpl extends SetupYoutubeWorkflow {
  import SetupYoutubeWorkflowImpl._

  private val logger = ZWorkflow.makeLogger

  private val youtubeActivities = ZWorkflow.newActivityStub[YoutubeActivities](
    ZActivityOptions
      .withStartToCloseTimeout(1.minute)
      .withRetryOptions(
        ZRetryOptions.default.withMaximumAttempts(5)
      )
  )

  private val telegramActivities = ZWorkflow.newActivityStub[TelegramActivities](
    ZActivityOptions
      .withStartToCloseTimeout(1.minute)
      .withRetryOptions(
        ZRetryOptions.default
          .withMaximumAttempts(5)
          .withDoNotRetry(
            nameOf[SubscriberNotFoundException]
          )
      )
  )

  private val state = ZWorkflowState.make[SetupState](SetupState.Initial)

  override def setup(params: SetupYoutubeParams): SetupResult = {
    pretendTyping(params)
    logger.info("Getting oauth2 creds...")
    val initResult = ZActivityStub.execute(
      youtubeActivities.initializeOAuth2(
        InitializeOAuth2Params(
          subscriber = params.subscriber,
          redirectUri = params.redirectUri
        )
      )
    )
    logger.info("Sending oauth uri to the user...")
    notifyUser(
      NotifySubscriberParams(
        params.subscriber,
        message = s"""|Please follow the link to add youtube integration:
                      |<a href="${initResult.authorizationUri}">Setup youtube</a>""".stripMargin,
        parseMode = Some(TelegramParseMode.Html)
      )
    )
    state := SetupState.WaitingForCallback
    logger.info("Waiting for the callback data...")
    val callbackDataReceived = ZWorkflow.awaitUntil(15.minutes)(
      state.exists(_.step == SetupYoutubeStep.GettingCredentials)
    )
    if (!callbackDataReceived) {
      notifyUser(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = s"Have you forgotten about Youtube integration?"
        )
      )
      state := SetupState.Failed
      SetupResult(SetupResult.Value.FailureReason("OAuth2 flow timeout"))
    } else {
      val SetupState.GettingCredentials(authorizationCode) = state.snapshot
      notifyUser(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = s"Setting up your Youtube integration..."
        )
      )
      pretendTyping(params)
      logger.info(s"Getting OAuth2 credentials...")
      val getCredsResult = ZActivityStub.execute(
        youtubeActivities.getOAuth2Credentials(
          GetOAuth2CredentialsParams(
            redirectUri = params.redirectUri,
            authorizationCode = authorizationCode
          )
        )
      )
      state := SetupState.Testing(getCredsResult.creds)
      logger.info("Testing credentials...")
      val testResult = ZActivityStub.execute(
        youtubeActivities.testOAuth2Credentials(
          TestOAuth2CredentialsParams(creds = getCredsResult.creds)
        )
      )
      testResult.error match {
        case Some(error) =>
          logger.info(s"Credentials aren't valid: $error")
          state := SetupState.Failed
          notifyUser(
            NotifySubscriberParams(
              subscriber = params.subscriber,
              message = s"The Youtube credentials aren't invalid. Please try again later"
            )
          )
          SetupResult(SetupResult.Value.FailureReason(error))
        case None =>
          state := SetupState.StoringTokens
          logger.info("Storing youtube credentials...")
          val storeResult = ZActivityStub.execute(
            youtubeActivities.storeYoutubeIntegration(
              StoreYoutubeIntegrationParams(
                params.subscriber,
                getCredsResult.creds
              )
            )
          )
          logger.info("Integration created successfully!")
          notifyUser(
            NotifySubscriberParams(
              subscriber = params.subscriber,
              message =
                s"Successfully created the Youtube integration!\nCheck available integrations with /${ListIntegrations.entryName}"
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

  override def provideCallbackData(callbackData: YoutubeCallbackData): Unit = {
    logger.info("Received callback data!")
    state := SetupState.GettingCredentials(callbackData.authorizationCode)
  }

  override def currentStep(): CurrentSetupYoutubeStep = {
    CurrentSetupYoutubeStep(
      value = state.snapshotOf(_.step)
    )
  }

  private def pretendTyping(params: SetupYoutubeParams): Unit = {
    ZActivityStub.execute(
      telegramActivities.pretendTyping(
        PretendTypingParams(params.subscriber)
      )
    )
  }

  private def notifyUser(params: NotifySubscriberParams): Unit = {
    ZActivityStub.execute(
      telegramActivities.notifySubscriber(params)
    )
  }
}
