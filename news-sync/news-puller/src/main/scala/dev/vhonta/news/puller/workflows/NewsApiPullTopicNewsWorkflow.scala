package dev.vhonta.news.puller.workflows

import dev.vhonta.news.puller.{
  NewsPullerActivityParameters,
  NewsPullerParameters,
  PullingResult,
  StoreArticlesParameters
}
import zio.temporal._
import zio._
import zio.temporal.workflow._
import zio.temporal.activity._
import zio.temporal.state.ZWorkflowState
import scala.annotation.tailrec

@workflowInterface
trait NewsApiPullTopicNewsWorkflow {
  @workflowMethod(name = "NewsApiPullTopic")
  def pull(parameters: NewsPullerParameters): PullingResult
}

class NewsApiPullTopicNewsWorkflowImpl extends NewsApiPullTopicNewsWorkflow {

  private val logger = ZWorkflow.getLogger(getClass)

  private val pageState = ZWorkflowState.make(1)

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

  private val databaseActivities = ZWorkflow
    .newActivityStub[DatabaseActivities]
    .withStartToCloseTimeout(1.minute)
    .withRetryOptions(
      ZRetryOptions.default.withMaximumAttempts(5)
    )
    .build

  override def pull(parameters: NewsPullerParameters): PullingResult = {
    @tailrec
    def process(processed: Long): Long = {
      val articles = ZActivityStub.execute(
        newsActivities.fetchArticles(
          NewsPullerActivityParameters(
            apiKey = parameters.apiKey,
            topic = parameters.topic,
            language = parameters.language,
            from = parameters.from,
            to = parameters.to,
            page = pageState.snapshot
          )
        )
      )
      // finish if no more results
      if (articles.articles.isEmpty) processed
      else {
        // save into database
        ZActivityStub.execute(
          databaseActivities.store(
            articles,
            storeParams = StoreArticlesParameters(
              topicId = parameters.topicId
            )
          )
        )
        // continue
        pageState.update(_ + 1)
        logger.info("Sleep for a bit before the next request...")
        ZWorkflow.sleep(30.seconds)
        process(processed + articles.articles.size)
      }
    }

    PullingResult(process(0))
  }
}
