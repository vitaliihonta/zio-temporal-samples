package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.Reader
import dev.vhonta.news.repository.{NewsFeedRepository, ReaderRepository}
import dev.vhonta.news.tgpush.internal.{HandlingDSL, TelegramHandler}
import dev.vhonta.news.tgpush.proto.{AddTopicParams, CurrentAddTopicStep}
import dev.vhonta.news.tgpush.workflow.AddTopicWorkflow
import io.temporal.client.WorkflowNotFoundException
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio._
import dev.vhonta.news.tgpush.{TelegramModule, proto}
import telegramium.bots._
import telegramium.bots.high.Api
import zio.temporal.protobuf.syntax._

object TopicsCommand extends HandlingDSL {
  val onListTopics: TelegramHandler[Api[Task] with NewsFeedRepository with ReaderRepository, Message] =
    onCommand(NewsSyncCommand.ListTopics) { msg =>
      ZIO
        .foreach(msg.from) { tgUser =>
          for {
            reader <- Repositories.getReaderWithSettings(tgUser)
            _ <- execute(
                   sendChatAction(
                     chatId = ChatIntId(msg.chat.id),
                     action = "typing"
                   )
                 )
            _ <- ZIO.logInfo(s"Listing topics reader=${reader.reader.id}")
            topics <- Repositories.listTopics(
                        readers = Some(Set(reader.reader.id))
                      )
            topicsStr = topics.view
                          .sortBy(_.lang.entryName)
                          .map { topic =>
                            s"<b>${topic.lang}</b> ${topic.topic} - ${topic.id}"
                          }
                          .mkString("\n")
            _ <- execute(
                   sendMessage(
                     chatId = ChatIntId(msg.chat.id),
                     text = s"Found the following topics 📝:  \n$topicsStr",
                     parseMode = Some(Html)
                   )
                 )
          } yield ()
        }
    }

  val onCreateTopic: TelegramHandler[Api[Task] with ZWorkflowClient with ReaderRepository, Message] =
    onCommand(NewsSyncCommand.CreateTopic) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          reader <- Repositories.getReaderWithSettings(tgUser)
          addTopicWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                                _.newWorkflowStub[AddTopicWorkflow]
                                  .withTaskQueue(TelegramModule.TaskQueue)
                                  .withWorkflowId(addTopicWorkflowId(reader.reader))
                                  .build
                              )
          _ <- ZWorkflowStub.start(
                 addTopicWorkflow.add(
                   AddTopicParams(reader.reader.id)
                 )
               )
          _ <- execute(
                 sendMessage(
                   chatId = ChatIntId(msg.chat.id),
                   text = "Please specify a topic you'd like to get updates for \uD83D\uDCDD:"
                 )
               )
        } yield ()
      }
    }

  val handleAddTopicFlow: TelegramHandler[Api[Task] with ZWorkflowClient with ReaderRepository, Message] =
    onMessage { msg =>
      whenSome(msg.from) { tgUser =>
        Repositories.getReaderWithSettings(tgUser).flatMap { reader =>
          whenSomeZIO(getCurrentAddTopicStepIfExists(reader.reader)) {
            case (addTopicWorkflow, step) if step.value.isWaitingForTopic =>
              whenSome(msg.text) { topic =>
                handled {
                  ZWorkflowStub.signal(
                    addTopicWorkflow.specifyTopic(
                      proto.SpecifyTopic(value = topic)
                    )
                  ) *> execute(
                    sendChatAction(
                      chatId = ChatIntId(msg.chat.id),
                      action = "typing"
                    )
                  )
                }
              }
            case _ => unhandled
          }
        }
      }
    }

  val all: TelegramHandler[Api[Task] with NewsFeedRepository with ReaderRepository with ZWorkflowClient, Message] =
    chain(
      onListTopics,
      onCreateTopic,
      handleAddTopicFlow
    )

  private def getCurrentAddTopicStepIfExists(
    reader: Reader
  ): RIO[ZWorkflowClient, Option[(ZWorkflowStub.Of[AddTopicWorkflow], CurrentAddTopicStep)]] = {
    for {
      addTopicWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                            _.newWorkflowStub[AddTopicWorkflow](
                              workflowId = addTopicWorkflowId(reader)
                            )
                          )
      result <- ZWorkflowStub
                  .query(
                    addTopicWorkflow.currentStep()
                  )
                  .map(addTopicWorkflow -> _)
                  .asSome
                  .catchSome { case _: WorkflowNotFoundException =>
                    ZIO.logInfo(s"Add topic for reader=${reader.id} not found") *>
                      ZIO.none
                  }
    } yield result
  }

  private def addTopicWorkflowId(reader: Reader): String =
    s"add-topic/${reader.id}"
}
