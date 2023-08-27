package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationNewsApiDetails,
  ContentFeedIntegrationType
}
import dev.vhonta.content.puller.proto.{ListTopics, NewsPullerParameters, NewsPullerTopic, PullerConfig}
import dev.vhonta.content.PullerStateValue
import dev.vhonta.content.puller.workflows.base.{AsyncScheduledPullerWorkflow, BaseScheduledPullerWorkflow}
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._
import java.time.LocalDateTime

@workflowInterface
trait NewsApiScheduledPullerWorkflow extends BaseScheduledPullerWorkflow

class NewsApiScheduledPullerWorkflowImpl
    extends AsyncScheduledPullerWorkflow[
      PullerStateValue.NewsApi,
      NewsPullerParameters,
      NewsApiPullWorkflow
    ](ContentFeedIntegrationType.news_api)
    with NewsApiScheduledPullerWorkflow {

  override protected def constructPullParams(
    integration:  ContentFeedIntegration,
    state:        Option[PullerStateValue.NewsApi],
    startedAt:    LocalDateTime,
    pullerConfig: PullerConfig
  ): Option[NewsPullerParameters] = {
    integration.integration match {
      case ContentFeedIntegrationNewsApiDetails(token, _) =>
        val topics = ZActivityStub.execute(
          databaseActivities.loadNewsTopics(
            ListTopics(List(integration.subscriber))
          )
        )

        logger.info(s"Going to pull ${topics.topics.size} topics")

        Some(
          NewsPullerParameters(
            integrationId = integration.id,
            apiKey = token,
            from = state.map(_.lastProcessedAt),
            to = startedAt,
            topics = topics.topics.map { topic =>
              NewsPullerTopic(
                topicId = topic.id,
                topic = topic.topic,
                lang = topic.lang
              )
            },
            datalakeOutputDir = pullerConfig.datalakeOutputDir
          )
        )
      case _ => None
    }
  }

  override protected def refreshIntegrationState(
    integrationId: Long,
    processedAt:   LocalDateTime
  ): PullerStateValue.NewsApi =
    PullerStateValue.NewsApi(processedAt)

  override protected def convertIntegrationState(raw: PullerStateValue): Option[PullerStateValue.NewsApi] =
    raw match {
      case newsApi: PullerStateValue.NewsApi => Some(newsApi)
      case _                                 => None
    }
}
