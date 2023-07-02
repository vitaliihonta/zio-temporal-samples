package dev.vhonta.content.puller.workflows.newsapi

import dev.vhonta.content.proto.ContentLanguage
import dev.vhonta.content.puller.proto.{
  NewsPullerActivityParameters,
  NewsPullerParameters,
  NewsPullerTopic,
  PullingResult,
  StoreArticlesParameters
}
import dev.vhonta.content.puller.workflows.base.BasePullWorkflow
import dev.vhonta.content.puller.workflows.storage.DatalakeActivities
import zio.temporal._
import zio._
import zio.temporal.workflow._
import zio.temporal.activity._
import scala.annotation.tailrec

@workflowInterface
trait NewsApiPullWorkflow extends BasePullWorkflow[NewsPullerParameters]

class NewsApiPullWorkflowImpl extends NewsApiPullWorkflow {
  private val logger = ZWorkflow.makeLogger

  private val newsActivities = ZWorkflow
    .newActivityStub[NewsActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(5)
        // bigger coefficient due for rate limiting
        .withBackoffCoefficient(3)
    )
    .build

  private val datalakeActivities = ZWorkflow
    .newActivityStub[DatalakeActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(5)
    )
    .build

  override def pull(params: NewsPullerParameters): PullingResult = {
    PullingResult(processAllTopics(params))
  }

  private def processAllTopics(
    params: NewsPullerParameters
  ): Long = {

    @tailrec
    def go(processed: Long, topicsLeft: List[NewsPullerTopic]): Long = {
      topicsLeft match {
        case Nil =>
          logger.info("All topics processed!")
          processed

        case topic :: rest =>
          logger.info(s"Processing topic=${topic.topic} id=${topic.topicId}")

          val topicProcessResult = processTopic(
            integrationId = params.integrationId,
            apiKey = params.apiKey,
            topic = topic.topic,
            topicId = topic.topicId,
            language = topic.lang,
            from = params.from,
            to = params.to,
            datalakeOutputDir = params.datalakeOutputDir
          )

          pauseBeforeNext()

          go(
            topicsLeft = rest,
            processed = processed + topicProcessResult
          )
      }
    }

    go(processed = 0, topicsLeft = params.topics.toList)
  }

  private def processTopic(
    integrationId:     Long,
    apiKey:            String,
    topic:             String,
    topicId:           zio.temporal.protobuf.UUID,
    language:          ContentLanguage,
    from:              Option[Long],
    to:                Long,
    datalakeOutputDir: String
  ): Long = {
    @tailrec
    def go(
      processed:   Long,
      currentPage: Int
    ): Long = {
      val articles = ZActivityStub.execute(
        newsActivities.fetchArticles(
          NewsPullerActivityParameters(
            apiKey = apiKey,
            topic = topic,
            language = language,
            from = from,
            to = to,
            page = currentPage
          )
        )
      )
      // finish if no more results
      if (articles.articles.isEmpty) processed
      else {
        // save into database
        ZActivityStub.execute(
          datalakeActivities.storeArticles(
            articles,
            storeParams = StoreArticlesParameters(
              integrationId = integrationId,
              topicId = topicId,
              datalakeOutputDir = datalakeOutputDir
            )
          )
        )

        pauseBeforeNext()

        go(
          processed = processed + articles.articles.size,
          currentPage = currentPage + 1
        )
      }
    }

    go(processed = 0, currentPage = 1)
  }

  private def pauseBeforeNext(): Unit = {
    logger.info("Sleep for a bit before the next request...")
    ZWorkflow.sleep(30.seconds)
  }
}
