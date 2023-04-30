package dev.vhonta.news.tgpush.workflow

import dev.vhonta.news.proto.NewsFeedRecommendationView
import zio._
import zio.temporal._
import zio.temporal.workflow._
import zio.temporal.activity._
import dev.vhonta.news.tgpush.proto.{
  ListRecommendationsParams,
  NotifyReaderParams,
  PretendTypingParams,
  PushRecommendationsParams,
  TelegramParseMode
}
import zio.temporal.protobuf.syntax._
import dev.vhonta.news.ProtoConverters._

import java.time.LocalDate

@workflowInterface
trait PushRecommendationsWorkflow {
  @workflowMethod
  def push(params: PushRecommendationsParams): Unit
}

class PushRecommendationsWorkflowImpl extends PushRecommendationsWorkflow {
  private val logger = ZWorkflow.getLogger(getClass)

  private val newsFeedActivities = ZWorkflow
    .newActivityStub[NewsFeedActivities]
    .withStartToCloseTimeout(10.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(3)
    )
    .build

  private val telegramActivities = ZWorkflow
    .newActivityStub[TelegramActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        .withDoNotRetry(
          nameOf[ReaderNotFoundException]
        )
    )
    .build

  override def push(params: PushRecommendationsParams): Unit = {
    logger.info("Going to push news feed recommendations...")

    val recommendations = ZActivityStub.execute(
      newsFeedActivities.listRecommendations(
        ListRecommendationsParams(
          params.readerWithSettings,
          date = params.date
        )
      )
    )

    logger.info(
      s"Have ${recommendations.results.size} topics to push with total ${recommendations.results.map(_.articles.size).sum} articles"
    )

    if (recommendations.results.isEmpty) {
      ZActivityStub.execute(
        telegramActivities.notifyReader(
          NotifyReaderParams(
            reader = params.readerWithSettings.reader.id,
            message = s"Nothing to show for ${params.date.fromProto[LocalDate]}...",
            parseMode = Some(TelegramParseMode.Html)
          )
        )
      )
    } else {
      ZActivityStub.execute(
        telegramActivities.notifyReader(
          NotifyReaderParams(
            reader = params.readerWithSettings.reader.id,
            message = s"Here is your recommendations for ${params.date.fromProto[LocalDate]}:",
            parseMode = Some(TelegramParseMode.Html)
          )
        )
      )
      for (recommendation <- recommendations.results) {
        ZActivityStub.execute(
          telegramActivities.pretendTyping(PretendTypingParams(params.readerWithSettings.reader.id))
        )
        ZActivityStub.execute(
          telegramActivities.notifyReader(
            NotifyReaderParams(
              reader = params.readerWithSettings.reader.id,
              message = buildMessage(recommendation),
              parseMode = Some(TelegramParseMode.Html)
            )
          )
        )
      }
    }
  }

  private def buildMessage(recommendation: NewsFeedRecommendationView): String = {
    val topic = recommendation.topic
    val articlesRendered = recommendation.articles.view.zipWithIndex
      .map { case (article, idx) =>
        s"""
           |<b>#${idx + 1}</b> ${article.title}.
           |<a href="${article.url}">Read more</a>
           |""".stripMargin
      }
      .mkString("\n")

    s"<b>$topic</b> ℹ️:\n$articlesRendered"
  }
}
