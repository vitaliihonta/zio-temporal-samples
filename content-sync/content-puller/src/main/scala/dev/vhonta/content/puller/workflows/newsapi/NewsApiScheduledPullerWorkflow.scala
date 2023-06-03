package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.proto.{
  ContentFeedIntegration,
  ContentFeedIntegrationNewsApiDetails,
  ContentFeedIntegrationType
}
import dev.vhonta.content.puller.proto.{
  ListTopics,
  NewsApiInitialPullerState,
  NewsApiIntegrationState,
  NewsPullerParameters,
  NewsPullerTopic
}
import dev.vhonta.content.puller.workflows.base.{AsyncScheduledPullerWorkflow, BaseScheduledPullerWorkflow}
import zio.temporal._
import zio.temporal.activity.ZActivityStub
import zio.temporal.protobuf.syntax._

import java.time.LocalDateTime

@workflowInterface
trait NewsApiScheduledPullerWorkflow extends BaseScheduledPullerWorkflow[NewsApiInitialPullerState]

class NewsApiScheduledPullerWorkflowImpl
    extends AsyncScheduledPullerWorkflow[
      NewsApiInitialPullerState,
      NewsApiIntegrationState,
      NewsPullerParameters,
      NewsApiPullWorkflow,
      NewsApiScheduledPullerWorkflow
    ](ContentFeedIntegrationType.news_api)
    with NewsApiScheduledPullerWorkflow {

  override protected def initializeState(
    initialState: NewsApiInitialPullerState
  ): Map[Long, NewsApiIntegrationState] = {
    initialState.values.view.map { state =>
      state.integrationId -> NewsApiIntegrationState(
        integrationId = state.integrationId,
        lastProcessedAt = state.lastProcessedAt
      )
    }.toMap
  }

  override protected def stateForNextRun(
    current: Map[Long, NewsApiIntegrationState]
  ): NewsApiInitialPullerState = {
    NewsApiInitialPullerState(
      values = current.view.values.toList
    )
  }

  override protected def constructPullParams(
    integration: ContentFeedIntegration,
    state:       Option[NewsApiIntegrationState],
    startedAt:   LocalDateTime
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
            }
          )
        )
      case _ => None
    }
  }

  override protected def refreshIntegrationState(
    integrationId: Long,
    processedAt:   LocalDateTime
  ): NewsApiIntegrationState =
    NewsApiIntegrationState(integrationId, processedAt.toProto)
}
