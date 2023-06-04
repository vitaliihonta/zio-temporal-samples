package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.ProtoConverters._
import dev.vhonta.content.repository.ContentFeedRepository
import dev.vhonta.content.tgbot.internal.TelegramHandler
import dev.vhonta.content.tgbot.proto.{AddTopicParams, AddTopicStep, CurrentAddTopicStep, PushRecommendationsParams}
import dev.vhonta.content.tgbot.workflow.AddTopicWorkflow
import dev.vhonta.content.tgbot.workflow.push.OnDemandPushRecommendationsWorkflow
import dev.vhonta.content.tgbot.{TelegramModule, proto}
import dev.vhonta.content.{Subscriber, proto => content_proto}
import io.temporal.client.WorkflowNotFoundException
import telegramium.bots._
import telegramium.bots.high.Api
import zio._
import zio.temporal.protobuf.syntax._
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}

object TopicsCommandHandler {
  val make: URLayer[SubscribersService with ContentFeedRepository with ZWorkflowClient, TopicsCommandHandler] =
    ZLayer.fromFunction(
      TopicsCommandHandler(
        _: SubscribersService,
        _: ContentFeedRepository,
        _: ZWorkflowClient
      )
    )
}

case class TopicsCommandHandler(
  subscribersService:    SubscribersService,
  contentFeedRepository: ContentFeedRepository,
  workflowClient:        ZWorkflowClient)
    extends BaseCommandHandler {

  private val onListTopics: TelegramHandler[Api[Task], Message] =
    onCommand(ContentSyncCommand.ListTopics) { msg =>
      ZIO
        .foreach(msg.from) { tgUser =>
          for {
            subscriber <- subscribersService.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
            _ <- execute(
                   sendChatAction(
                     chatId = ChatIntId(msg.chat.id),
                     action = "typing"
                   )
                 )
            _ <- ZIO.logInfo(s"Listing topics subscriber=${subscriber.subscriber.id}")
            topics <- contentFeedRepository.listTopics(
                        subscribers = Some(Set(subscriber.subscriber.id))
                      )
            topicsButtons = topics.view.map { topic =>
                              List(
                                ContentSyncCallbackQuery.TopicDetails
                                  .toInlineKeyboardButton(text = topic.topic, topicId = topic.id)
                              )
                            }.toList
            _ <- execute(
                   sendMessage(
                     chatId = ChatIntId(msg.chat.id),
                     text = s"Found the following topics 📝: ",
                     parseMode = Some(Html),
                     replyMarkup = Some(
                       InlineKeyboardMarkup(topicsButtons)
                     )
                   )
                 )
          } yield ()
        }
    }

  val onGetTopicDetails: TelegramHandler[Api[Task], CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.TopicDetails) { (query, topicId) =>
      ZIO.foreach(query.message) { msg =>
        for {
          topic <- contentFeedRepository.findTopicById(topicId)
          _ <- ZIO
                 .foreach(topic) { topic =>
                   val text = s"<b>${topic.topic} 📝</b> (language: ${topic.lang})"
                   val markup = InlineKeyboardMarkup(
                     List(
                       List(
                         ContentSyncCallbackQuery.DeleteTopic
                           .toInlineKeyboardButton("Delete", topicId)
                       )
                     )
                   )
                   execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = text,
                       parseMode = Some(Html),
                       replyMarkup = Some(markup)
                     )
                   )
                 }
                 .someOrElseZIO(
                   execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = s"Topic #$topicId <b>NOT FOUND</b>",
                       parseMode = Some(Html)
                     )
                   )
                 )
        } yield ()
      }
    }

  private val onDeleteTopic: TelegramHandler[Api[Task], CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.DeleteTopic) { (query, topicId) =>
      ZIO.foreach(query.message) { msg =>
        for {
          topic <- contentFeedRepository.findTopicById(topicId)

          _ <- ZIO
                 .foreach(topic) { topic =>
                   contentFeedRepository.deleteTopicById(topicId) *>
                     execute(
                       sendMessage(
                         chatId = ChatIntId(msg.chat.id),
                         text = s"Topic ${topic.topic} (id #$topicId) <b>DELETED</b>",
                         parseMode = Some(Html)
                       )
                     )
                 }
                 .someOrElseZIO(
                   execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = s"Topic #$topicId <b>NOT FOUND</b>",
                       parseMode = Some(Html)
                     )
                   )
                 )
        } yield ()
      }
    }

  private val onCreateTopic: TelegramHandler[Api[Task], Message] =
    onCommand(ContentSyncCommand.CreateTopic) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- subscribersService.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          addTopicWorkflow <- workflowClient
                                .newWorkflowStub[AddTopicWorkflow]
                                .withTaskQueue(TelegramModule.TaskQueue)
                                .withWorkflowId(addTopicWorkflowId(subscriber.subscriber))
                                .build
          _ <- ZWorkflowStub.start(
                 addTopicWorkflow.add(
                   AddTopicParams(
                     subscriber.subscriber.id
                   )
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

  private val handleAddTopicFlow: TelegramHandler[Api[Task], Message] =
    onMessage { msg =>
      whenSome(msg.from) { tgUser =>
        subscribersService.getOrCreateByTelegramId(tgUser, msg.chat, msg.date).flatMap { subscriber =>
          whenSomeZIO(getCurrentAddTopicStepIfExists(subscriber.subscriber)) {
            case (addTopicWorkflow, CurrentAddTopicStep(AddTopicStep.WaitingForTopic, _)) =>
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

  private val handleSetTopicLanguageFlow: TelegramHandler[Api[Task], CallbackQuery] =
    onCallbackQuery(ContentSyncCallbackQuery.AddTopicSetLanguage) { (query, language) =>
      ZIO.foreach(query.message) { msg =>
        subscribersService.getOrCreateByTelegramId(query.from, msg.chat, msg.date).flatMap { subscriber =>
          getCurrentAddTopicStepIfExists(subscriber.subscriber)
            .flatMap {
              case Some((addTopicWorkflow, CurrentAddTopicStep(AddTopicStep.WaitingForLanguage, _))) =>
                for {
                  _ <- ZIO.logInfo("Is waiting for language, sending signal!")
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
                  _ <- execute(
                         sendChatAction(
                           chatId = ChatIntId(msg.chat.id),
                           action = "typing"
                         )
                       )
                  _ <- ZWorkflowStub.signal(
                         addTopicWorkflow.specifyLanguage(
                           proto.SpecifyLanguage(value = language)
                         )
                       )
                } yield ()

              case _ => ZIO.unit
            }
        }
      }
    }

  private val onLatestFeed: TelegramHandler[Api[Task], Message] =
    onCommand(ContentSyncCommand.LatestFeed) { msg =>
      ZIO.foreach(msg.from) { tgUser =>
        for {
          subscriber <- subscribersService.getOrCreateByTelegramId(tgUser, msg.chat, msg.date)
          pushWorkflow <- workflowClient
                            .newWorkflowStub[OnDemandPushRecommendationsWorkflow]
                            .withTaskQueue(TelegramModule.TaskQueue)
                            .withWorkflowId(s"on-demand/push/${subscriber.subscriber.id}")
                            .withWorkflowExecutionTimeout(5.minutes)
                            .build
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

  override val messageHandlers: TelegramHandler[Api[Task], Message] =
    chain(
      onLatestFeed,
      onListTopics,
      onCreateTopic,
      handleAddTopicFlow
    )

  override val callbackQueryHandlers: TelegramHandler[Api[Task], CallbackQuery] =
    chain(
      handleSetTopicLanguageFlow,
      onGetTopicDetails,
      onDeleteTopic
    )

  private def getCurrentAddTopicStepIfExists(
    subscriber: Subscriber
  ): Task[Option[(ZWorkflowStub.Of[AddTopicWorkflow], CurrentAddTopicStep)]] = {
    for {
      addTopicWorkflow <- workflowClient.newWorkflowStub[AddTopicWorkflow](
                            workflowId = addTopicWorkflowId(subscriber)
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
      _ <- ZIO.logInfo(s"Current add topic step is ${result.map(_._2)}")
    } yield result
  }

  private def addTopicWorkflowId(subscriber: Subscriber): String =
    s"add-topic/${subscriber.id}"
}
