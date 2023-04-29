package dev.vhonta.news.tgpush

import dev.vhonta.news.Reader
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, ReaderRepository}
import dev.vhonta.news.tgpush.internal.{
  TelegramCommandHandling,
  TelegramCommandId,
  TelegramCommandIdEnum,
  TelegramQueryCallbackId,
  TelegramQueryCallbackIdEnum,
  TelegramQueryHandling
}
import zio._
import zio.interop.catz._
import telegramium.bots._
import telegramium.bots.high._

import java.sql.SQLException

object NewsSyncBot {
  val make: URLayer[ReaderRepository with NewsFeedIntegrationRepository with Api[Task], NewsSyncBot] =
    ZLayer.fromFunction(NewsSyncBot(_: ReaderRepository, _: NewsFeedIntegrationRepository)(_: Api[Task]))
}

sealed abstract class NewsSyncCommand(val description: String) extends TelegramCommandId

object NewsSyncCommand extends TelegramCommandIdEnum[NewsSyncCommand] {
  case object Start extends NewsSyncCommand("Start the bot")
  case object List  extends NewsSyncCommand("List syncs")

  override val values = findValues
}

sealed trait NewsSyncCallbackQuery extends TelegramQueryCallbackId
object NewsSyncCallbackQuery extends TelegramQueryCallbackIdEnum[NewsSyncCallbackQuery] {
  case object NewerMind    extends NewsSyncCallbackQuery
  case object Setup        extends NewsSyncCallbackQuery
  case object SetupNewsApi extends NewsSyncCallbackQuery

  override val values = findValues
}

case class NewsSyncBot(
  readerRepository:          ReaderRepository,
  integrationRepository:     NewsFeedIntegrationRepository
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

  private val onList = onCommand(NewsSyncCommand.List) { msg =>
    ZIO
      .foreach(msg.from) { tgUser =>
        for {
          reader <- readerRepository
                      .findByTelegramId(tgUser.id)
                      .someOrFail(
                        new Exception(s"User not found with tg_id=${tgUser.id}")
                      )
          integrations <- integrationRepository.findAllOwnedBy(reader.id)
          integrationsStr = integrations
                              .map { integration =>
                                s"""*${integration.integration.`type`.entryName}*: ||${integration.integration}|| """
                              }
                              .mkString("  \n")
        } yield List(
          sendMessage(
            chatId = ChatIntId(msg.chat.id),
            text = s"Found the following integrations:  \n$integrationsStr",
            parseMode = Some(Markdown)
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
              text = "Please specify your News API token:"
            )
          )
        )
      }
      .map(_.toList.flatten)
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

  override def onMessage(msg: Message): Task[Unit] = {
    List(onStart, onList)
      .onMessage(msg)
      .getOrElse {
        msg.from match {
          case Some(user) if !user.isBot =>
            ZIO.logInfo(
              s"Received a message from ${user.firstName} ${user.lastName} id=${user.id} msg=${msg.text} data=${msg}"
            )
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
}
