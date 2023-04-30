package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.repository.ReaderRepository
import dev.vhonta.news.tgpush.bot.NewsSyncBot
import dev.vhonta.news.tgpush.proto.{NotifyReaderParams, TelegramParseMode}
import telegramium.bots
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import java.util.UUID

@activityInterface
trait TelegramActivities {
  @throws[ReaderNotFoundException]
  def notifyReader(params: NotifyReaderParams): Unit
}

case class ReaderNotFoundException(readerId: UUID) extends Exception(s"Reader with id=$readerId not found")

object TelegramActivitiesImpl {
  val make: URLayer[ReaderRepository with NewsSyncBot with ZActivityOptions[Any], TelegramActivities] =
    ZLayer.fromFunction(TelegramActivitiesImpl(_: ReaderRepository, _: NewsSyncBot)(_: ZActivityOptions[Any]))
}

case class TelegramActivitiesImpl(
  readerRepository: ReaderRepository,
  bot:              NewsSyncBot
)(implicit options: ZActivityOptions[Any])
    extends TelegramActivities {

  override def notifyReader(params: NotifyReaderParams): Unit =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Notifying reader=${params.reader.fromProto} about the status")
        reader <- readerRepository
                    .findById(params.reader.fromProto)
                    .someOrFail(ReaderNotFoundException(params.reader.fromProto))
        _ <- ZIO.logInfo("Pushing a telegram notification...")
        parseMode = params.parseMode.flatMap {
                      case TelegramParseMode.Html            => Some(bots.Html)
                      case TelegramParseMode.Markdown2       => Some(bots.Markdown2)
                      case TelegramParseMode.Unrecognized(_) => None
                    }
        _ <- bot.notifyReader(reader, params.message, parseMode)
      } yield ()
    }
}
