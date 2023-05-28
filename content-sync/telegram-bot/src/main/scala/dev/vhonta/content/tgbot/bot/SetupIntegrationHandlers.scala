package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.{Subscriber, SubscriberSettings}
import dev.vhonta.content.repository.SubscriberRepository
import dev.vhonta.content.tgbot.TelegramModule
import dev.vhonta.content.tgbot.internal.TelegramCallbackQuery.NoData
import dev.vhonta.content.tgbot.internal.{HandlingDSL, TelegramHandler}
import dev.vhonta.content.tgbot.proto.{CurrentSetupNewsApiStep, SetupNewsApiParams, SetupYoutubeParams}
import dev.vhonta.content.tgbot.workflow.{SetupNewsApiWorkflow, SetupYoutubeWorkflow}
import io.temporal.client.WorkflowNotFoundException
import telegramium.bots._
import telegramium.bots.high.Api
import zio._
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.tgbot.proto

object SetupIntegrationHandlers extends HandlingDSL {
  val onStart: TelegramHandler[Api[Task] with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.Start) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
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

  val onNeverMind: TelegramHandler[Api[Task], CallbackQuery] =
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

  val onSetupNewsApi: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.SetupNewsApi) { (query, _) =>
      ZIO.foreach(query.message) { msg =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(query.from, msg.chat, msg.date)
          setupWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                             _.newWorkflowStub[SetupNewsApiWorkflow]
                               .withTaskQueue(TelegramModule.TaskQueue)
                               .withWorkflowId(setupNewsApiWorkflowId(subscriber.subscriber))
                               .build
                           )
          _ <- ZWorkflowStub.start(
                 setupWorkflow.setup(
                   SetupNewsApiParams(subscriber.subscriber.id)
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

  val handleNewsApiSetup: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, Message] =
    onMessage { msg =>
      whenSome(msg.from) { tgUser =>
        Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date).flatMap { subscriber =>
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

  val onSetupYoutube: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.SetupYoutube) { (query, _) =>
      ZIO.foreach(query.message) { msg =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(query.from, msg.chat, msg.date)
          setupWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                             _.newWorkflowStub[SetupYoutubeWorkflow]
                               .withTaskQueue(TelegramModule.TaskQueue)
                               .withWorkflowId(setupYoutubeWorkflowId(subscriber.subscriber))
                               .build
                           )
          _ <- ZWorkflowStub.start(
                 setupWorkflow.setup(
                   SetupYoutubeParams(
                     subscriber.subscriber.id,
                     redirectUri = "http://localhost:9092/oauth2" /*TODO: make configurable*/
                   )
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
    }

  val messageHandlers: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, Message] =
    chain(onStart, handleNewsApiSetup)

  val callbackQueryHandlers: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, CallbackQuery] =
    chain(onSetupNewsApi, onSetupYoutube, onNeverMind)

  private def getCurrentSetupStepIfExists(
    subscriber: Subscriber
  ): RIO[ZWorkflowClient, Option[(ZWorkflowStub.Of[SetupNewsApiWorkflow], CurrentSetupNewsApiStep)]] = {
    for {
      setupWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                         _.newWorkflowStub[SetupNewsApiWorkflow](
                           workflowId = setupNewsApiWorkflowId(subscriber)
                         )
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

  private def settingsHtml(settings: SubscriberSettings): String = {
    s"""\n<b>Timezone:</b> ${settings.timezone}\n<b>Publish at: ${settings.publishAt}</b>"""
  }
}
