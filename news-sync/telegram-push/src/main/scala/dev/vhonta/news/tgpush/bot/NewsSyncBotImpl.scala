package dev.vhonta.news.tgpush.bot

import dev.vhonta.news.Reader
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, NewsFeedRepository, ReaderRepository}
import dev.vhonta.news.tgpush.internal.HandlingDSL
import telegramium.bots._
import telegramium.bots.high.{Api, LongPollBot}
import zio._
import zio.temporal.workflow.ZWorkflowClient
import zio.interop.catz._

object NewsSyncBotImpl {
  val make: URLayer[
    ReaderRepository with NewsFeedRepository with NewsFeedIntegrationRepository with ZWorkflowClient with Api[Task],
    NewsSyncBot
  ] =
    ZLayer.fromFunction(
      NewsSyncBotImpl(_: ReaderRepository, _: NewsFeedRepository, _: NewsFeedIntegrationRepository, _: ZWorkflowClient)(
        _: Api[Task]
      )
    )
}

case class NewsSyncBotImpl(
  readerRepository:      ReaderRepository,
  newsFeedRepository:    NewsFeedRepository,
  integrationRepository: NewsFeedIntegrationRepository,
  workflowClient:        ZWorkflowClient
)(implicit api:          Api[Task])
    extends LongPollBot[Task](api)
    with NewsSyncBot
    with HandlingDSL {

  override def prepare(): Task[Unit] = {
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

  override def notifyReader(reader: Reader, message: String, parseMode: Option[ParseMode]): Task[Unit] =
    api
      .execute(
        sendMessage(
          chatId = ChatIntId(reader.telegramChatId),
          text = message,
          parseMode = parseMode
        )
      )
      .unit

  private val depsLayer: ULayer[
    ReaderRepository with NewsFeedRepository with NewsFeedIntegrationRepository with ZWorkflowClient with Api[Task]
  ] =
    ZLayer.succeed(readerRepository) ++
      ZLayer.succeed(newsFeedRepository) ++
      ZLayer.succeed(integrationRepository) ++
      ZLayer.succeed(workflowClient) ++
      ZLayer.succeed(api)

  private val messageHandlers = chain(
    TopicsCommand.all,
    SettingsCommands.all,
    SetupNewsApiHandlers.messageHandlers
  )

  private val callbackQueryHandlers =
    SetupNewsApiHandlers.callbackQueryHandlers

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
