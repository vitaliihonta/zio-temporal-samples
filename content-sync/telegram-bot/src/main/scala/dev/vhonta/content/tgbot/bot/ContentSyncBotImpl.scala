package dev.vhonta.content.tgbot.bot

import dev.vhonta.content.Subscriber
import dev.vhonta.content.repository.{ContentFeedIntegrationRepository, ContentFeedRepository, SubscriberRepository}
import dev.vhonta.content.tgbot.internal.HandlingDSL
import telegramium.bots._
import telegramium.bots.high.{Api, LongPollBot}
import zio._
import zio.temporal.workflow.ZWorkflowClient
import zio.interop.catz._

object ContentSyncBotImpl {
  val make: URLayer[
    SubscriberRepository
      with ContentFeedRepository
      with ContentFeedIntegrationRepository
      with ZWorkflowClient
      with Api[Task],
    ContentSyncBot
  ] =
    ZLayer.fromFunction(
      ContentSyncBotImpl(_: SubscriberRepository,
                         _: ContentFeedRepository,
                         _: ContentFeedIntegrationRepository,
                         _: ZWorkflowClient
      )(
        _: Api[Task]
      )
    )
}

case class ContentSyncBotImpl(
  subscriberRepository:  SubscriberRepository,
  contentFeedRepository: ContentFeedRepository,
  integrationRepository: ContentFeedIntegrationRepository,
  workflowClient:        ZWorkflowClient
)(implicit api:          Api[Task])
    extends LongPollBot[Task](api)
    with ContentSyncBot
    with HandlingDSL {

  override def prepare(): Task[Unit] = {
    ZIO.logInfo("Setting bot commands...") *>
      api
        .execute(
          setMyCommands(
            commands = ContentSyncCommand.botCommands,
            scope = Some(BotCommandScopeAllPrivateChats)
          )
        )
        .unit
  }

  override def notifySubscriber(subscriber: Subscriber, message: String, parseMode: Option[ParseMode]): Task[Unit] =
    api
      .execute(
        sendMessage(
          chatId = ChatIntId(subscriber.telegramChatId),
          text = message,
          parseMode = parseMode
        )
      )
      .unit

  override def pretendTyping(subscriber: Subscriber): Task[Unit] =
    api
      .execute(
        sendChatAction(
          chatId = ChatIntId(subscriber.telegramChatId),
          action = "typing"
        )
      )
      .unit

  private val depsLayer: ULayer[
    SubscriberRepository
      with ContentFeedRepository
      with ContentFeedIntegrationRepository
      with ZWorkflowClient
      with Api[Task]
  ] =
    ZLayer.succeed(subscriberRepository) ++
      ZLayer.succeed(contentFeedRepository) ++
      ZLayer.succeed(integrationRepository) ++
      ZLayer.succeed(workflowClient) ++
      ZLayer.succeed(api)

  private val messageHandlers = chain(
    SetupNewsApiHandlers.messageHandlers,
    TopicsCommand.all,
    SettingsCommands.messageHandlers
  )

  private val callbackQueryHandlers = chain(
    SetupNewsApiHandlers.callbackQueryHandlers,
    SettingsCommands.callbackQueryHandlers
  )

  override def onMessage(msg: Message): Task[Unit] = {
    messageHandlers
      .handle(msg)
      .handleOrElseZIO {
        msg.from match {
          case Some(user) if !user.isBot =>
            ZIO.logInfo(
              s"Received a message from ${user.firstName} ${user.lastName} id=${user.id} msg=${msg.text}"
            )
          case _ =>
            ZIO.logInfo("Not a user message, skip")
        }
      }
      .provide(depsLayer)
      .unit
  }

  override def onCallbackQuery(query: CallbackQuery): Task[Unit] = {
    callbackQueryHandlers
      .handle(query)
      .handleOrElseZIO {
        ZIO.logInfo(s"Received query=$query")
      }
      .provide(depsLayer)
      .unit
  }
}
