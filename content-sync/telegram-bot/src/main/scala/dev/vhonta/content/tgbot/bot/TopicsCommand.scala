package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.{proto => content_proto}
import dev.vhonta.content.Subscriber
import dev.vhonta.content.repository.{ContentFeedRepository, SubscriberRepository}
import dev.vhonta.content.tgbot.internal.{HandlingDSL, TelegramHandler}
import dev.vhonta.content.tgbot.proto.{AddTopicParams, CurrentAddTopicStep, PushRecommendationsParams}
import dev.vhonta.content.tgbot.workflow.AddTopicWorkflow
import io.temporal.client.WorkflowNotFoundException
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio._
import dev.vhonta.content.tgbot.{TelegramModule, proto}
import telegramium.bots._
import telegramium.bots.high.Api
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.tgbot.workflow.push.{OnDemandPushRecommendationsWorkflow, PushRecommendationsWorkflow}

object TopicsCommand extends HandlingDSL {
  val onListTopics: TelegramHandler[Api[Task] with ContentFeedRepository with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.ListTopics) { msg =>
      ZIO
        .foreach(msg.from) { tgUser =>
          for {
            subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
            _ <- execute(
                   sendChatAction(
                     chatId = ChatIntId(msg.chat.id),
                     action = "typing"
                   )
                 )
            _ <- ZIO.logInfo(s"Listing topics subscriber=${subscriber.subscriber.id}")
            topics <- Repositories.listTopics(
                        subscribers = Some(Set(subscriber.subscriber.id))
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

  val onCreateTopic: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.CreateTopic) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          addTopicWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                                _.newWorkflowStub[AddTopicWorkflow]
                                  .withTaskQueue(TelegramModule.TaskQueue)
                                  .withWorkflowId(addTopicWorkflowId(subscriber.subscriber))
                                  .build
                              )
          _ <- ZWorkflowStub.start(
                 addTopicWorkflow.add(
                   AddTopicParams(subscriber.subscriber.id)
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

  val handleAddTopicFlow: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, Message] =
    onMessage { msg =>
      whenSome(msg.from) { tgUser =>
        Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date).flatMap { subscriber =>
          whenSomeZIO(getCurrentAddTopicStepIfExists(subscriber.subscriber)) {
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

  val onLatestFeed: TelegramHandler[Api[Task] with ZWorkflowClient with SubscriberRepository, Message] =
    onCommand(ContentSyncCommand.LatestFeed) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- Repositories.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          pushWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                            _.newWorkflowStub[OnDemandPushRecommendationsWorkflow]
                              .withTaskQueue(TelegramModule.TaskQueue)
                              .withWorkflowId(s"on-demand/push/${subscriber.subscriber.id}")
                              .withWorkflowExecutionTimeout(5.minutes)
                              .build
                          )
          now <- ZIO.clockWith(_.localDateTime)
          _ <- ZWorkflowStub.start(
                 pushWorkflow.push(
                   PushRecommendationsParams(
                     subscriberWithSettings = content_proto.SubscriberWithSettings(
                       subscriber = content_proto.Subscriber(
                         id = subscriber.subscriber.id,
                         registeredAt = subscriber.subscriber.registeredAt
                       ),
                       settings = content_proto.SubscriberSettings(
                         subscriber = subscriber.settings.subscriber,
                         modifiedAt = subscriber.settings.modifiedAt,
                         timezone = subscriber.settings.timezone,
                         publishAt = subscriber.settings.publishAt
                       )
                     ),
                     date = now
                   )
                 )
               )
          _ <- execute(
                 sendChatAction(
                   chatId = ChatIntId(msg.chat.id),
                   action = "typing"
                 )
               )
        } yield ()
      }
    }

  val all
    : TelegramHandler[Api[Task] with ContentFeedRepository with SubscriberRepository with ZWorkflowClient, Message] =
    chain(
      onLatestFeed,
      onListTopics,
      onCreateTopic,
      handleAddTopicFlow
    )

  private def getCurrentAddTopicStepIfExists(
    subscriber: Subscriber
  ): RIO[ZWorkflowClient, Option[(ZWorkflowStub.Of[AddTopicWorkflow], CurrentAddTopicStep)]] = {
    for {
      addTopicWorkflow <- ZIO.serviceWithZIO[ZWorkflowClient](
                            _.newWorkflowStub[AddTopicWorkflow](
                              workflowId = addTopicWorkflowId(subscriber)
                            )
                          )
      result <- ZWorkflowStub
                  .query(
                    addTopicWorkflow.currentStep()
                  )
                  .map(addTopicWorkflow -> _)
                  .asSome
                  .catchSome { case _: WorkflowNotFoundException =>
                    ZIO.logInfo(s"Add topic for subscriber=${subscriber.id} not found") *>
                      ZIO.none
                  }
    } yield result
  }

  private def addTopicWorkflowId(subscriber: Subscriber): String =
    s"add-topic/${subscriber.id}"
}
