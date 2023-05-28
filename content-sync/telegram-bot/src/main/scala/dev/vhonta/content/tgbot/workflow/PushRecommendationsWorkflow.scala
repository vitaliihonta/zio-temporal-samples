package dev.vhonta.content.tgbot.workflow

import dev.vhonta.content.proto.ContentFeedRecommendationView
import zio._
import zio.temporal._
import zio.temporal.workflow._
import zio.temporal.activity._
import dev.vhonta.content.tgbot.proto.{
  ListRecommendationsParams,
  NotifySubscriberParams,
  PretendTypingParams,
  PushRecommendationsParams,
  TelegramParseMode
}
import zio.temporal.protobuf.syntax._
import dev.vhonta.content.ProtoConverters._
import java.time.LocalDate

@workflowInterface
trait PushRecommendationsWorkflow {
  @workflowMethod
  def push(params: PushRecommendationsParams): Unit
}

class PushRecommendationsWorkflowImpl extends PushRecommendationsWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val newsFeedActivities = ZWorkflow
    .newActivityStub[ContentFeedActivities]
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
          nameOf[SubscriberNotFoundException]
        )
    )
    .build

  override def push(params: PushRecommendationsParams): Unit = {
    logger.info("Going to push content feed recommendations...")

    val recommendations = ZActivityStub.execute(
      newsFeedActivities.listRecommendations(
        ListRecommendationsParams(
          params.subscriberWithSettings,
          date = params.date
        )
      )
    )

    logger.info(
      s"Have ${recommendations.results.size} topics to push with total ${recommendations.results.map(_.items.size).sum} items"
    )

    if (recommendations.results.isEmpty) {
      ZActivityStub.execute(
        telegramActivities.notifySubscriber(
          NotifySubscriberParams(
            subscriber = params.subscriberWithSettings.subscriber.id,
            message = s"Nothing to show for ${params.date.fromProto[LocalDate]}...",
            parseMode = Some(TelegramParseMode.Html)
          )
        )
      )
    } else {
      ZActivityStub.execute(
        telegramActivities.notifySubscriber(
          NotifySubscriberParams(
            subscriber = params.subscriberWithSettings.subscriber.id,
            message = s"Here is your recommendations for ${params.date.fromProto[LocalDate]}:",
            parseMode = Some(TelegramParseMode.Html)
          )
        )
      )
      for (recommendation <- recommendations.results) {
        ZActivityStub.execute(
          telegramActivities.pretendTyping(PretendTypingParams(params.subscriberWithSettings.subscriber.id))
        )
        ZActivityStub.execute(
          telegramActivities.notifySubscriber(
            NotifySubscriberParams(
              subscriber = params.subscriberWithSettings.subscriber.id,
              message = buildMessage(recommendation),
              parseMode = Some(TelegramParseMode.Html)
            )
          )
        )
      }
    }
  }

  private def buildMessage(recommendation: ContentFeedRecommendationView): String = {
    val integration = {
      // TODO: decouple conversion
      val kind =
        if (recommendation.integration.integration.isYoutube) "Youtube ▶\uFE0F"
        else "NewsApi ℹ\uFE0F"
      s"<b>#${recommendation.integration.id}</b> - <b>$kind</b>"
    }

    val itemsRendered = {
      if (recommendation.items.isEmpty) "Data is not available yet..."
      else {
        recommendation.items.view.zipWithIndex
          .map { case (item, idx) =>
            s"""
               |<b>#${idx + 1}</b> ${item.title}.
               |<a href="${item.url}">More</a>
               |""".stripMargin
          }
          .mkString("\n")
      }
    }

    s"$integration:\n$itemsRendered"
  }
}
