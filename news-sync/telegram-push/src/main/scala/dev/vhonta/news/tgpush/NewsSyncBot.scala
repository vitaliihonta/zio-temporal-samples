package dev.vhonta.news.tgpush

import dev.vhonta.news.{NewsFeedIntegrationDetails, Reader}
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, NewsFeedRepository, ReaderRepository}
import dev.vhonta.news.tgpush.NewsSyncCallbackQuery.SetupNewsApi
import dev.vhonta.news.tgpush.internal.{
  TelegramCommandHandling,
  TelegramCommandId,
  TelegramCommandIdEnum,
  TelegramQueryCallbackId,
  TelegramQueryCallbackIdEnum,
  TelegramQueryHandling
}
import dev.vhonta.news.tgpush.proto.{
  AddTopicParams,
  CurrentAddTopicStep,
  CurrentSetupStep,
  SetupParams,
  SetupStep,
  SpecifyTopic
}
import dev.vhonta.news.tgpush.workflow.{AddTopicWorkflow, SetupNewsApiWorkflow}
import io.temporal.client.WorkflowNotFoundException
import zio._
import zio.interop.catz._
import zio.temporal.protobuf.syntax._
import telegramium.bots._
import telegramium.bots.high._
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}

import java.sql.SQLException

sealed abstract class NewsSyncCommand(val description: String) extends TelegramCommandId

object NewsSyncCommand extends TelegramCommandIdEnum[NewsSyncCommand] {
  case object Start       extends NewsSyncCommand("Start the bot")
  case object CreateTopic extends NewsSyncCommand("Create a topic")

  case object ListTopics extends NewsSyncCommand("List topics")

  case object ListIntegrations extends NewsSyncCommand("List integrations")

  override val values = findValues
}

sealed trait NewsSyncCallbackQuery extends TelegramQueryCallbackId
object NewsSyncCallbackQuery extends TelegramQueryCallbackIdEnum[NewsSyncCallbackQuery] {
  case object NewerMind    extends NewsSyncCallbackQuery
  case object Setup        extends NewsSyncCallbackQuery
  case object SetupNewsApi extends NewsSyncCallbackQuery

  override val values = findValues
}

object NewsSyncBot {
  val make: URLayer[
    ReaderRepository with NewsFeedRepository with NewsFeedIntegrationRepository with ZWorkflowClient with Api[Task],
    NewsSyncBot
  ] =
    ZLayer.fromFunction(
      NewsSyncBot(_: ReaderRepository, _: NewsFeedRepository, _: NewsFeedIntegrationRepository, _: ZWorkflowClient)(
        _: Api[Task]
      )
    )
}

case class NewsSyncBot(
  readerRepository:          ReaderRepository,
  newsFeedRepository:        NewsFeedRepository,
  integrationRepository:     NewsFeedIntegrationRepository,
  workflowClient:            ZWorkflowClient
)(implicit override val api: Api[Task])
    extends LongPollBot[Task](api)
    with TelegramCommandHandling[NewsSyncCommand]
    with TelegramQueryHandling[NewsSyncCallbackQuery] {

  def prepare(): Task[Unit] = {
    ZIO.logInfo("Setting bot commands...") *>
      api
        .execute(
          setMyCommands(
            commands = NewsSyncCommand.botCommands,
            scope = Some(BotCommandScopeAllPrivateChats)
          )
        )
        .unit
  }

  def notifyReader(reader: Reader, message: String, parseMode: Option[ParseMode]): Task[Unit] =
    api
      .execute(
        sendMessage(
          chatId = ChatIntId(reader.telegramChatId),
          text = message,
          parseMode = parseMode
        )
      )
      .unit

  // TODO: implement
  def sendNewsFeed(): Task[Unit] =
    ???

  private val onStart = onCommand[Any](NewsSyncCommand.Start) { msg =>
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader <- readerRepository
                      .findByTelegramId(tgUser.id)
                      .someOrElseZIO(createReader(tgUser, msg.chat))
        } yield {
          List(
            sendMessage(
              chatId = ChatIntId(reader.telegramChatId),
              text = "Welcome to the news sync! What do you want to read?",
              replyMarkup = Some(
                InlineKeyboardMarkup(
                  List(
                    List(
                      NewsSyncCallbackQuery.NewerMind.toInlineKeyboardButton("Never mind")
                    ),
                    List(
                      NewsSyncCallbackQuery.SetupNewsApi.toInlineKeyboardButton("News API")
                    )
                  )
                )
              )
            )
          )
        }
      }
      .map(_.toList.flatten)
  }

  private val onListTopics = onCommand(NewsSyncCommand.ListTopics) { msg =>
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader <- readerRepository
                      .findByTelegramId(tgUser.id)
                      .someOrElseZIO(createReader(tgUser, msg.chat))
          _ <- ZIO.logInfo(s"Listing topics reader=${reader.id}")
          topics <- newsFeedRepository.listTopics(
                      readers = Some(Set(reader.id))
                    )
          topicsStr = topics.view
                        .sortBy(_.lang.entryName)
                        .map { topic =>
                          s"<b>${topic.lang}</b> ${topic.topic} - ${topic.id}"
                        }
                        .mkString("\n")
        } yield {
          List(
            sendMessage(
              chatId = ChatIntId(msg.chat.id),
              text = s"Found the following topics:  \n$topicsStr",
              parseMode = Some(Html)
            )
          )
        }
      }
      .map(_.toList.flatten)
  }

  private val onCreateTopics = onCommand(NewsSyncCommand.CreateTopic) { msg =>
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader <- getReader(tgUser)
          addTopicWorkflow <- workflowClient
                                .newWorkflowStub[AddTopicWorkflow]
                                .withTaskQueue(TelegramModule.TaskQueue)
                                .withWorkflowId(addTopicWorkflowId(reader))
                                .build
          _ <- ZWorkflowStub.start(
                 addTopicWorkflow.add(
                   AddTopicParams(reader.id)
                 )
               )
        } yield {
          List(
            sendMessage(
              chatId = ChatIntId(msg.chat.id),
              text = "Please specify a topic you'd like to get updates for:"
            )
          )
        }
      }
      .map(_.toList.flatten)
  }

  private val onListIntegrations = onCommand(NewsSyncCommand.ListIntegrations) { msg =>
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader       <- getReader(tgUser)
          integrations <- integrationRepository.findAllOwnedBy(reader.id)
          integrationsStr = integrations.view
                              .sortBy(_.integration.`type`.entryName)
                              .map { integration =>
                                integration.integration match {
                                  case NewsFeedIntegrationDetails.NewsApi(apiKey) =>
                                    s""" #<b>${integration.id}</b> - <b>${integration.integration.`type`.entryName}</b>: <tg-spoiler>$apiKey</tg-spoiler>"""
                                }
                              }
                              .mkString("  \n")
        } yield List(
          sendMessage(
            chatId = ChatIntId(msg.chat.id),
            text = s"Found the following integrations:  \n$integrationsStr",
            parseMode = Some(Html)
          )
        )
      }
      .map(_.toList.flatten)
  }

  private val onNeverMind = onCallbackQuery[Any](NewsSyncCallbackQuery.NewerMind) { query =>
    ZIO
      .foreach(query.message) { msg =>
        ZIO.succeed(
          List(
            answerCallbackQuery(callbackQueryId = query.id),
            editMessageReplyMarkup(
              chatId = Some(ChatIntId(msg.chat.id)),
              messageId = Some(msg.messageId),
              replyMarkup = None
            ),
            sendMessage(
              chatId = ChatIntId(msg.chat.id),
              text = "I got you homie"
            )
          )
        )
      }
      .map(_.toList.flatten)
  }

  private val onSetup = onCallbackQuery[Any](NewsSyncCallbackQuery.Setup) { query =>
    ZIO
      .foreach(query.message) { msg =>
        ZIO.succeed(
          List(
            answerCallbackQuery(callbackQueryId = query.id),
            editMessageReplyMarkup(
              chatId = Some(ChatIntId(msg.chat.id)),
              messageId = Some(msg.messageId),
              replyMarkup = None
            ),
            sendMessage(
              chatId = ChatIntId(msg.chat.id),
              text = "What source to sync from?",
              replyMarkup = Some(
                InlineKeyboardMarkup(
                  List(
                    List(NewsSyncCallbackQuery.NewerMind.toInlineKeyboardButton("Never mind")),
                    List(
                      NewsSyncCallbackQuery.SetupNewsApi.toInlineKeyboardButton("News API")
                    )
                  )
                )
              )
            )
          )
        )
      }
      .map(_.toList.flatten)
  }

  private val onSetupNewsApi = onCallbackQuery[Any](NewsSyncCallbackQuery.SetupNewsApi) { query =>
    ZIO
      .foreach(query.message) { msg =>
        for {
          reader <- getReader(query.from)
          setupWorkflow <- workflowClient
                             .newWorkflowStub[SetupNewsApiWorkflow]
                             .withTaskQueue(TelegramModule.TaskQueue)
                             .withWorkflowId(setupNewsApiWorkflowId(reader))
                             .build
          _ <- ZWorkflowStub.start(
                 setupWorkflow.setup(
                   SetupParams(reader.id)
                 )
               )
        } yield {
          List(
            answerCallbackQuery(callbackQueryId = query.id),
            editMessageReplyMarkup(
              chatId = Some(ChatIntId(msg.chat.id)),
              messageId = Some(msg.messageId),
              replyMarkup = None
            ),
            sendMessage(
              chatId = ChatIntId(msg.chat.id),
              text = "Please specify your News API key (you need an account here https://newsapi.org/pricing):"
            )
          )
        }
      }
      .map(_.toList.flatten)
  }

  override def onMessage(msg: Message): Task[Unit] = {
    List(onStart, onListIntegrations, onListTopics, onCreateTopics)
      .onMessage(msg)
      .getOrElse {
        msg.from match {
          case Some(user) if !user.isBot =>
            ZIO.logInfo(
              s"Received a message from ${user.firstName} ${user.lastName} id=${user.id} msg=${msg.text}"
            ) *> handleSetupFlow(msg) *> handleAddTopicFlow(msg)
          case _ =>
            ZIO.logInfo("Not a user message, skip")
        }
      }
      .unit
  }

  override def onCallbackQuery(query: CallbackQuery): Task[Unit] = {
    List(onNeverMind, onSetup, onSetupNewsApi)
      .onCallbackQuery(query)
      .getOrElse {
        ZIO.logInfo(s"Received query=$query")
      }
      .unit
  }

  private def handleAddTopicFlow(msg: Message): Task[Unit] = {
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader           <- getReader(tgUser)
          _                <- ZIO.logInfo(s"Getting current add topic step for reader=${reader.id}")
          maybeCurrentStep <- getCurrentAddTopicStepIfExists(reader)
          _ <- ZIO.foreach(maybeCurrentStep) {
                 case (addTopicWorkflow, step) if step.value.isWaitingForTopic =>
                   ZIO.foreach(msg.text) { topic =>
                     ZWorkflowStub.signal(
                       addTopicWorkflow.specifyTopic(
                         SpecifyTopic(value = topic)
                       )
                     )
                   }
                 case _ => ZIO.unit
               }
        } yield ()
      }
      .unit
  }

  private def handleSetupFlow(msg: Message): Task[Unit] = {
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader           <- getReader(tgUser)
          _                <- ZIO.logInfo(s"Getting current setup step for reader=${reader.id}")
          maybeCurrentStep <- getCurrentSetupStepIfExists(reader)
          _ <- ZIO.foreach(maybeCurrentStep) {
                 case (setupWorkflow, step) if step.value.isWaitingForApiKey =>
                   ZIO.foreach(msg.text) { apiKey =>
                     ZWorkflowStub.signal(
                       setupWorkflow.provideApiKey(
                         proto.SetupNewsApi(apiKey)
                       )
                     )
                   }
                 case (_, step) if step.value.isValidatingKey =>
                   api.execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = "Wait a little bit, we're checking if your API key is valid"
                     )
                   )
                 case _ =>
                   api.execute(
                     sendMessage(
                       chatId = ChatIntId(msg.chat.id),
                       text = "Almost there, we're preparing your sync integration..."
                     )
                   )
               }
        } yield ()
      }
      .unit
  }

  private def getCurrentAddTopicStepIfExists(
    reader: Reader
  ): Task[Option[(ZWorkflowStub.Of[AddTopicWorkflow], CurrentAddTopicStep)]] = {
    for {
      addTopicWorkflow <- workflowClient.newWorkflowStub[AddTopicWorkflow](
                            workflowId = addTopicWorkflowId(reader)
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

  private def getCurrentSetupStepIfExists(
    reader: Reader
  ): Task[Option[(ZWorkflowStub.Of[SetupNewsApiWorkflow], CurrentSetupStep)]] = {
    for {
      setupWorkflow <- workflowClient.newWorkflowStub[SetupNewsApiWorkflow](
                         workflowId = setupNewsApiWorkflowId(reader)
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

  private def createReader(tgUser: User, chat: Chat): IO[SQLException, Reader] =
    for {
      _        <- ZIO.logInfo(s"Going to create a new reader ${tgUser.firstName} ${tgUser.lastName} id=${tgUser.id}")
      readerId <- ZIO.randomWith(_.nextUUID)
      now      <- ZIO.clockWith(_.localDateTime)
      reader <- readerRepository.create(
                  Reader(
                    id = readerId,
                    registeredAt = now,
                    telegramId = tgUser.id,
                    telegramChatId = chat.id
                  )
                )
    } yield reader

  private def addTopicWorkflowId(reader: Reader): String =
    s"add-topic/${reader.id}"

  private def setupNewsApiWorkflowId(reader: Reader): String =
    s"setup/news-api/${reader.id}"

  private def getReader(tgUser: User): Task[Reader] = {
    readerRepository
      .findByTelegramId(tgUser.id)
      .someOrFail(
        new Exception(s"User not found with tg_id=${tgUser.id}")
      )
  }
}
