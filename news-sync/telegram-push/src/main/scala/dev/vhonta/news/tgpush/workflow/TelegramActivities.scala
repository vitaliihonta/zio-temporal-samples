package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.{NewsFeedIntegration, NewsFeedIntegrationDetails, NewsTopicLanguage}
import dev.vhonta.news.client.{EverythingRequest, NewsApiClient, NewsApiRequestError, SortBy}
import dev.vhonta.news.repository.{NewsFeedIntegrationRepository, ReaderRepository}
import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.protobuf.syntax._
import dev.vhonta.news.tgpush.proto.NotifyCreateIntegrationResultParams
import dev.vhonta.news.proto
import dev.vhonta.news.tgpush.NewsSyncBot

import java.util.UUID

@activityInterface
trait TelegramActivities {
  @throws[ReaderNotFoundException]
  def notifyCreateIntegrationResult(params: NotifyCreateIntegrationResultParams): Unit
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

  override def notifyCreateIntegrationResult(params: NotifyCreateIntegrationResultParams): Unit =
    ZActivity.run {
      for {
        _ <- ZIO.logInfo(s"Notifying reader=${params.reader.fromProto} about the status")
        reader <- readerRepository
                    .findById(params.reader.fromProto)
                    .someOrFail(ReaderNotFoundException(params.reader.fromProto))
        _ <- ZIO.logInfo("Pushing a telegram notification...")
        _ <- bot.notifyAboutIntegration(reader, params.message)
      } yield ()
    }
}
