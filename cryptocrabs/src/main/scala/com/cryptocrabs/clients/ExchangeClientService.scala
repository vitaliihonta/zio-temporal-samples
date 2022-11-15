package com.cryptocrabs.clients

import com.cryptocrabs.TaskQueues
import com.cryptocrabs.workflows.ExchangeWorkflow
import zio.*
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio.temporal.ZRetryOptions
import zio.temporal.protobuf.syntax.*

import java.util.UUID
import com.cryptocrabs.exchange.*

object ExchangeClientService {
  val make: URLayer[ZWorkflowClient, ExchangeClientService] = ZLayer.fromFunction(new ExchangeClientService(_))
}

class ExchangeClientService(client: ZWorkflowClient) {
  def exchangeOrder(sender: UUID, receiver: UUID, amount: BigDecimal): Task[Unit] =
    for {
      orderId <- Random.nextUUID
      exchangeWorkflow <- client
                            .newWorkflowStub[ExchangeWorkflow]
                            .withTaskQueue(TaskQueues.exchanger)
                            .withWorkflowId(orderId.toString)
                            .withWorkflowExecutionTimeout(5.minutes)
                            .withWorkflowRunTimeout(10.seconds)
                            .withRetryOptions(
                              ZRetryOptions.default.withMaximumAttempts(5)
                            )
                            .build
      _ <- ZIO.logInfo("Going to trigger workflow")
      _ <- ZWorkflowStub
             .start(
               exchangeWorkflow.exchangeOrder(
                 ExchangeOrder(
                   id = orderId,
                   sender = sender,
                   receiver = receiver,
                   amount = amount
                 )
               )
             )
             .orDieWith(_.error)
      workflowStub <- client.newWorkflowStubProxy[ExchangeWorkflow](workflowId = orderId.toString)
      _ <- workflowStub
             .result[Unit]
             .orDieWith(_.error)
    } yield ()
}
