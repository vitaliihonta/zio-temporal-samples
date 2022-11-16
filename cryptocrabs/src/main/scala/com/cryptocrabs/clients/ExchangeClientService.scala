package com.cryptocrabs.clients

import com.cryptocrabs.TaskQueues
import com.cryptocrabs.workflows.{ExchangeWorkflow, ProtoConverters}
import zio.*
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio.temporal.ZRetryOptions
import zio.temporal.protobuf.syntax.*
import com.cryptocrabs.workflows.ProtoConverters.given

import java.util.UUID
import com.cryptocrabs.*
import com.cryptocrabs.exchange.{ExchangeOrderRequest, ExchangeOrderView}

object ExchangeClientService {
  val make: URLayer[ZWorkflowClient, ExchangeClientService] = ZLayer.fromFunction(new ExchangeClientService(_))
}

class ExchangeClientService(client: ZWorkflowClient) {
  def exchangeOrder(seller: UUID, amount: BigDecimal, currency: CryptoCurrency): Task[UUID] =
    for {
      orderId <- Random.nextUUID
      exchangeWorkflow <- client
                            .newWorkflowStub[ExchangeWorkflow]
                            .withTaskQueue(TaskQueues.exchanger)
                            .withWorkflowId(orderId.toString)
                            // NOTE: timeouts should consider "sleep" and "awaitUntil" insided the workflow
                            .withWorkflowExecutionTimeout(5.minutes)
                            .withWorkflowRunTimeout(5.minutes)
                            .withRetryOptions(
                              ZRetryOptions.default.withMaximumAttempts(3)
                            )
                            .build
      _ <- ZIO.logInfo("Going to trigger workflow")
      _ <- ZWorkflowStub
             .start(
               exchangeWorkflow.exchangeOrder(
                 ExchangeOrderRequest(
                   seller = seller,
                   amount = amount,
                   currency = currency
                 )
               )
             )
             .orDieWith(_.error)
    } yield orderId

  def waitForResult(orderId: UUID): Task[ExchangeOrder] =
    for {
      workflowStub <- client.newWorkflowStubProxy[ExchangeWorkflow](workflowId = orderId.toString)
      result <- workflowStub
                  .result[ExchangeOrderView]
                  .orDieWith(_.error)
    } yield viewToModel(result)

  def getStatus(orderId: UUID): Task[ExchangeOrder] =
    for {
      workflowStub <- client.newWorkflowStubProxy[ExchangeWorkflow](workflowId = orderId.toString)
      result <- ZWorkflowStub
                  .query(
                    workflowStub.transactionState()
                  )
                  .orDieWith(_.error)
    } yield viewToModel(result)

  private def viewToModel(orderView: ExchangeOrderView): ExchangeOrder =
    ExchangeOrder(
      orderId = orderView.id.fromProto,
      sellerId = orderView.seller.map(_.fromProto),
      amount = orderView.amount.map(_.fromProto),
      currency = orderView.currency.map(_.fromProto),
      status = orderView.status.fromProto,
      buyerInfo = orderView.buyerInfo.map { buyerInfo =>
        ExchangeOrderBuyer(id = buyerInfo.buyerId.fromProto, screenshotUrl = buyerInfo.screenshotUrl)
      }
    )
}
