package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.{Subscriber, SubscriberWithSettings}
import dev.vhonta.content.tgbot.TelegramModule
import dev.vhonta.content.tgbot.internal.TelegramCallbackQuery.NoData
import dev.vhonta.content.tgbot.internal.TelegramHandler
import dev.vhonta.content.tgbot.proto.{CurrentSetupNewsApiStep, SetupNewsApiParams, SetupParams, SetupYoutubeParams}
import io.temporal.client.WorkflowNotFoundException
import telegramium.bots._
import telegramium.bots.high.Api
import zio._
import zio.temporal.workflow.{IsWorkflow, ZWorkflowClient, ZWorkflowStub}
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.tgbot.proto
import dev.vhonta.content.tgbot.workflow.setup.{BaseSetupWorkflow, SetupNewsApiWorkflow, SetupYoutubeWorkflow}
import scala.reflect.ClassTag

object SetupIntegrationHandlers {
  case class SetupIntegrationsConfig(
    youtubeRedirectUri: String)

  val config: Config[SetupIntegrationsConfig] =
    Config
      .string("youtube_redirect_uri")
      .map(SetupIntegrationsConfig(_))
      .nested("telegram", "setup")

  val make: ZLayer[SubscribersService with ZWorkflowClient, Config.Error, SetupIntegrationHandlers] =
    ZLayer.fromZIO(ZIO.config(config)) >>>
      ZLayer.fromFunction(
        SetupIntegrationHandlers(
          _: SetupIntegrationsConfig,
          _: SubscribersService,
          _: ZWorkflowClient
        )
      )
}

case class SetupIntegrationHandlers(
  config:             SetupIntegrationHandlers.SetupIntegrationsConfig,
  subscribersService: SubscribersService,
  workflowClient:     ZWorkflowClient)
    extends BaseCommandHandler {

  private val onStart: TelegramHandler[Api[Task], Message] =
    onCommand(ContentSyncCommand.Start) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- subscribersService.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          _ <- execute(
                 sendMessage(
                   chatId = ChatIntId(subscriber.subscriber.telegramChatId),
                   text = "Welcome to the content sync bot! What do you want to follow?",
                   replyMarkup = Some(
                     InlineKeyboardMarkup(
                       List(
                         List(
                           ContentSyncCallbackQuery.NewerMind.toInlineKeyboardButton("Never mind", NoData)
                         ),
                         List(
                           ContentSyncCallbackQuery.SetupNewsApi.toInlineKeyboardButton("News API \uD83D\uDCF0", NoData)
                         ),
                         List(
                           ContentSyncCallbackQuery.SetupYoutube.toInlineKeyboardButton("Youtube ▶\uFE0F", NoData)
                         )
                       )
                     )
                   )
                 )
               )
        } yield ()
      }
    }

  private val onNeverMind: TelegramHandler[Api[Task], CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.NewerMind) { (query, _) =>
      ZIO.foreach(query.message) { msg =>
        for {
          _ <- execute(answerCallbackQuery(callbackQueryId = query.id))
          _ <- execute(
                 editMessageReplyMarkup(
                   chatId = Some(ChatIntId(msg.chat.id)),
                   messageId = Some(msg.messageId),
                   replyMarkup = None
                 )
               )
          _ <- execute(
                 sendMessage(
                   chatId = ChatIntId(msg.chat.id),
                   text = "I got you homie"
                 )
               )
        } yield ()
      }
    }

  private val onSetupNewsApi: TelegramHandler[Api[Task], CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.SetupNewsApi) { (query, _) =>
      ZIO.foreach(query.message) { msg =>
        for {
          _ <- startSetupWorkflow[SetupNewsApiParams, SetupNewsApiWorkflow](query, msg)(
                 setupNewsApiWorkflowId,
                 makeParams = subscriber => SetupNewsApiParams(subscriber.subscriber.id)
               )
          _ <-
            execute(
              sendMessage(
                chatId = ChatIntId(msg.chat.id),
                text =
                  "Please specify your News API key \uD83D\uDD11\n(you need an account here https://newsapi.org/pricing):"
              )
            )
        } yield ()
      }
    }

  private val handleNewsApiSetup: TelegramHandler[Api[Task], Message] =
    onMessage { msg =>
      whenSome(msg.from) { tgUser =>
        subscribersService.getOrCreateByTelegramId(tgUser, msg.chat, msg.date).flatMap { subscriber =>
          whenSomeZIO(getCurrentSetupStepIfExists(subscriber.subscriber)) {
            case (setupWorkflow, step) if step.value.isWaitingForApiKey =>
              whenSome(msg.text) { apiKey =>
                handled {
                  ZWorkflowStub.signal(
                    setupWorkflow.provideApiKey(
                      proto.ProvideNewsApiKeyData(apiKey)
                    )
                  ) *> execute(
                    sendChatAction(
                      chatId = ChatIntId(msg.chat.id),
                      action = "typing"
                    )
                  )
                }
              }
            case (_, step) if step.value.isValidatingKey =>
              handled {
                execute(
                  sendMessage(
                    chatId = ChatIntId(msg.chat.id),
                    text = "Wait a little bit, we're checking if your API key is valid"
                  )
                )
              }
            case _ => unhandled
          }
        }
      }
    }

  private val onSetupYoutube: TelegramHandler[Api[Task], CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.SetupYoutube) { (query, _) =>
      ZIO.foreach(query.message) { msg =>
        startSetupWorkflow[SetupYoutubeParams, SetupYoutubeWorkflow](query, msg)(
          makeWorkflowId = setupYoutubeWorkflowId,
          makeParams = subscriber =>
            SetupYoutubeParams(
              subscriber.subscriber.id,
              redirectUri = config.youtubeRedirectUri
            )
        )
      }
    }

  override val messageHandlers: TelegramHandler[Api[Task], Message] =
    chain(onStart, handleNewsApiSetup)

  override val callbackQueryHandlers: TelegramHandler[Api[Task], CallbackQuery] =
    chain(onSetupNewsApi, onSetupYoutube, onNeverMind)

  private def startSetupWorkflow[
    Params <: SetupParams,
    SetupWorkflow <: BaseSetupWorkflow[Params]: IsWorkflow: ClassTag
  ](query:          CallbackQuery,
    msg:            Message
  )(makeWorkflowId: Subscriber => String,
    makeParams:     SubscriberWithSettings => Params
  ) = {
    for {
      subscriber <- subscribersService.getOrCreateByTelegramId(query.from, msg.chat, msg.date)
      setupWorkflow <- workflowClient
                         .newWorkflowStub[SetupWorkflow]
                         .withTaskQueue(TelegramModule.TaskQueue)
                         .withWorkflowId(makeWorkflowId(subscriber.subscriber))
                         .build
      _ <- ZWorkflowStub.start(
             setupWorkflow.setup(
               makeParams(subscriber)
             )
           )
      _ <- execute(
             answerCallbackQuery(callbackQueryId = query.id)
           )
      _ <- execute(
             editMessageReplyMarkup(
               chatId = Some(ChatIntId(msg.chat.id)),
               messageId = Some(msg.messageId),
               replyMarkup = None
             )
           )
    } yield ()
  }

  private def getCurrentSetupStepIfExists(
    subscriber: Subscriber
  ): Task[Option[(ZWorkflowStub.Of[SetupNewsApiWorkflow], CurrentSetupNewsApiStep)]] = {
    for {
      setupWorkflow <- workflowClient.newWorkflowStub[SetupNewsApiWorkflow](
                         workflowId = setupNewsApiWorkflowId(subscriber)
                       )
      result <- ZWorkflowStub
                  .query(
                    setupWorkflow.currentStep()
                  )
                  .map(setupWorkflow -> _)
                  .asSome
                  .catchSome { case _: WorkflowNotFoundException =>
                    ZIO.logInfo(s"Setup for subscriber=${subscriber.id} not found") *>
                      ZIO.none
                  }
    } yield result
  }

  private def setupNewsApiWorkflowId(subscriber: Subscriber): String =
    s"setup/news-api/${subscriber.id}"

  private def setupYoutubeWorkflowId(subscriber: Subscriber): String =
    SetupYoutubeWorkflow.workflowId(subscriber.id)
}
