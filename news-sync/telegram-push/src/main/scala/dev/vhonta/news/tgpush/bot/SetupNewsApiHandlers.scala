package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.{Reader, ReaderSettings}
import dev.vhonta.news.repository.ReaderRepository
import dev.vhonta.news.tgpush.TelegramModule
import dev.vhonta.news.tgpush.internal.{HandlingDSL, TelegramHandler}
import dev.vhonta.news.tgpush.proto.{CurrentSetupStep, SetupParams}
import dev.vhonta.news.tgpush.workflow.SetupNewsApiWorkflow
import io.temporal.client.WorkflowNotFoundException
import telegramium.bots._
import telegramium.bots.high.Api
import zio._
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio.temporal.protobuf.syntax._
import dev.vhonta.news.tgpush.proto

object SetupNewsApiHandlers extends HandlingDSL {
  val onStart: TelegramHandler[Api[Task] with ReaderRepository, Message] =
    onCommand(NewsSyncCommand.Start) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          reader <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          _ <- execute(
                 sendMessage(
                   chatId = ChatIntId(reader.reader.telegramChatId),
                   text = "Welcome to the news sync! What do you want to read?",
                   replyMarkup = Some(
                     InlineKeyboardMarkup(
                       List(
                         List(
                           NewsSyncCallbackQuery.NewerMind.toInlineKeyboardButton("Never mind")
                         ),
                         List(
                           NewsSyncCallbackQuery.SetupNewsApi.toInlineKeyboardButton("News API \uD83D\uDCF0")
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
    onCallbackQueryId(NewsSyncCallbackQuery.NewerMind) { query =>
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

  val onSetupNewsApi: TelegramHandler[Api[Task] with ZWorkflowClient with ReaderRepository, CallbackQuery] =
    onCallbackQueryId(NewsSyncCallbackQuery.SetupNewsApi) { query =>
      ZIO.foreach(query.message) { msg =>
        for {
          reader <- Repositories.getOrCreateByTelegramId(query.from, msg.chat, msg.date)
          setupWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                             _.newWorkflowStub[SetupNewsApiWorkflow]
                               .withTaskQueue(TelegramModule.TaskQueue)
                               .withWorkflowId(setupNewsApiWorkflowId(reader.reader))
                               .build
                           )
          _ <- ZWorkflowStub.start(
                 setupWorkflow.setup(
                   SetupParams(reader.reader.id)
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

  val handleNewsApiSetup: TelegramHandler[Api[Task] with ZWorkflowClient with ReaderRepository, Message] =
    onMessage { msg =>
      whenSome(msg.from) { tgUser =>
        Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date).flatMap { reader =>
          whenSomeZIO(getCurrentSetupStepIfExists(reader.reader)) {
            case (setupWorkflow, step) if step.value.isWaitingForApiKey =>
              whenSome(msg.text) { apiKey =>
                handled {
                  ZWorkflowStub.signal(
                    setupWorkflow.provideApiKey(
                      proto.SetupNewsApi(apiKey)
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

  val messageHandlers: TelegramHandler[Api[Task] with ZWorkflowClient with ReaderRepository, Message] =
    chain(onStart, handleNewsApiSetup)

  val callbackQueryHandlers: TelegramHandler[Api[Task] with ZWorkflowClient with ReaderRepository, CallbackQuery] =
    chain(onSetupNewsApi, onNeverMind)

  private def getCurrentSetupStepIfExists(
    reader: Reader
  ): RIO[ZWorkflowClient, Option[(ZWorkflowStub.Of[SetupNewsApiWorkflow], CurrentSetupStep)]] = {
    for {
      setupWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                         _.newWorkflowStub[SetupNewsApiWorkflow](
                           workflowId = setupNewsApiWorkflowId(reader)
                         )
                       )
      result <- ZWorkflowStub
                  .query(
                    setupWorkflow.currentStep()
                  )
                  .map(setupWorkflow -> _)
                  .asSome
                  .catchSome { case _: WorkflowNotFoundException =>
                    ZIO.logInfo(s"Setup for reader=${reader.id} not found") *>
                      ZIO.none
                  }
    } yield result
  }

  private def setupNewsApiWorkflowId(reader: Reader): String =
    s"setup/news-api/${reader.id}"

  private def settingsHtml(settings: ReaderSettings): String = {
    s"""\n<b>Timezone:</b> ${settings.timezone}\n<b>Publish at: ${settings.publishAt}</b>"""
  }
}
