package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.proto.ContentLanguage
import dev.vhonta.content.tgbot.bot.ContentSyncCallbackQuery
import dev.vhonta.content.tgbot.proto._
import dev.vhonta.content.tgbot.workflow.common.{ContentFeedActivities, SubscriberNotFoundException, TelegramActivities}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import zio.temporal.state.ZWorkflowState
import zio.temporal.workflow._

@workflowInterface
trait AddTopicWorkflow {
  @workflowMethod
  def add(params: AddTopicParams): Unit

  @signalMethod
  def specifyTopic(command: SpecifyTopic): Unit

  @signalMethod
  def specifyLanguage(command: SpecifyLanguage): Unit

  @queryMethod
  def currentStep(): CurrentAddTopicStep
}

class AddTopicWorkflowImpl extends AddTopicWorkflow {

  private val logger = ZWorkflow.makeLogger

  private val contentFeedActivities = ZWorkflow.newActivityStub[ContentFeedActivities](
    ZActivityOptions
      .withStartToCloseTimeout(10.seconds)
      .withRetryOptions(
        ZRetryOptions.default.withMaximumAttempts(3)
      )
  )

  private val telegramActivities = ZWorkflow.newActivityStub[TelegramActivities](
    ZActivityOptions
      .withStartToCloseTimeout(1.minute)
      .withRetryOptions(
        ZRetryOptions.default
          .withMaximumAttempts(5)
          .withDoNotRetry(nameOf[SubscriberNotFoundException])
      )
  )

  private val stepState     = ZWorkflowState.make[AddTopicStep](AddTopicStep.WaitingForTopic)
  private val topicState    = ZWorkflowState.empty[String]
  private val languageState = ZWorkflowState.empty[ContentLanguage]

  override def add(params: AddTopicParams): Unit = {
    logger.info("Waiting for the topic...")

    val topicSet = ZWorkflow.awaitUntil(12.hours)(stepState =:= AddTopicStep.WaitingForLanguage)
    if (!topicSet) {
      stopWorkflow(params)
    } else {
      val topic = topicState.snapshot
      notifyUserIgnoreError(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = "Please select language for your topic: ",
          replyMarkup = Some(
            InlineKeyboardMarkup(
              ContentLanguage.values.view
                .grouped(2)
                .map { group =>
                  InlineKeyboardButtonGroup(
                    group.view.map { lang =>
                      val flag = lang match {
                        case ContentLanguage.English   => "\uD83C\uDDEC\uD83C\uDDE7"
                        case ContentLanguage.French    => "\uD83C\uDDEB\uD83C\uDDF7"
                        case ContentLanguage.Spanish   => "\uD83C\uDDEA\uD83C\uDDF8"
                        case ContentLanguage.Ukrainian => "\uD83C\uDDFA\uD83C\uDDE6"
                      }
                      val button = ContentSyncCallbackQuery.AddTopicSetLanguage
                        .toInlineKeyboardButton(s"$flag ${lang.name}", (lang: ContentLanguage).fromProto)

                      InlineKeyboardButton(
                        text = button.text,
                        url = button.url,
                        callbackData = button.callbackData
                      )
                    }.toList
                  )
                }
                .toList
            )
          )
        )
      )
      logger.info("Waiting for the language...")
      val languageSet = ZWorkflow.awaitUntil(12.hours)(stepState =:= AddTopicStep.StoringTopic)
      if (!languageSet) {
        stopWorkflow(params)
      } else {
        val language = languageState.snapshot
        ZActivityStub.execute(
          contentFeedActivities.createTopic(
            CreateTopicParams(
              subscriber = params.subscriber,
              topic = topic,
              lang = language
            )
          )
        )
      }
      notifyUserIgnoreError(
        NotifySubscriberParams(
          subscriber = params.subscriber,
          message = s"Topic <b>$topic</b> successfully created! Wait for news feed next evening 🙌",
          parseMode = Some(TelegramParseMode.Html)
        )
      )
    }
  }

  override def specifyTopic(command: SpecifyTopic): Unit = {
    logger.info(s"Specified topic=${command.value}")
    topicState := command.value
    stepState  := AddTopicStep.WaitingForLanguage
  }

  override def specifyLanguage(command: SpecifyLanguage): Unit = {
    logger.info(s"Specified language=${command.value}")
    languageState := command.value
    stepState     := AddTopicStep.StoringTopic
  }

  override def currentStep(): CurrentAddTopicStep = {
    CurrentAddTopicStep(value = stepState.snapshot)
  }

  private def stopWorkflow(params: AddTopicParams): Unit = {
    notifyUserIgnoreError(
      NotifySubscriberParams(
        subscriber = params.subscriber,
        message = s"Have you forgotten about new topic sync?"
      )
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
