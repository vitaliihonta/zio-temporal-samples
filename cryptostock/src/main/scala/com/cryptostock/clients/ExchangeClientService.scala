package com.cryptostock.clients

import com.cryptostock.TaskQueues
import com.cryptostock.workflows.{ExchangeWorkflow, ProtoConverters}
import zio.*
import zio.temporal.workflow.{ZWorkflowClient, ZWorkflowStub}
import zio.temporal.ZRetryOptions
import zio.temporal.protobuf.syntax.*
import com.cryptostock.workflows.ProtoConverters.given
import java.util.UUID
import com.cryptostock.*
import com.cryptostock.exchange.{
  AcceptExchangeOrderSignal,
  BuyerConfirmationSignal,
  ExchangeOrderRequest,
  ExchangeOrderResult,
  ExchangeOrderState
}

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
                            // NOTE: timeouts should consider "sleep" and "awaitUntil" inside the workflow
                            .withWorkflowExecutionTimeout(5.minutes)
                            .withWorkflowRunTimeout(5.minutes)
                            .withRetryOptions(
                              ZRetryOptions.default.withMaximumAttempts(3)
                            )
                            .build
      _ <- ZIO.logInfo("Going to trigger workflow")
      _ <- ZWorkflowStub.start(
             exchangeWorkflow.exchangeOrder(
               ExchangeOrderRequest(
                 seller = seller,
                 amount = amount,
                 currency = currency
               )
             )
           )
    } yield orderId

  def waitForResult(orderId: UUID): Task[ExchangeOrder] =
    for {
      workflowStub <- client.newWorkflowStub[ExchangeWorkflow](workflowId = orderId.toString)
      result       <- workflowStub.result[ExchangeOrderResult]
    } yield resultToOrder(result)

  def getStatus(orderId: UUID): Task[ExchangeOrderStatus] =
    for {
      workflowStub <- client.newWorkflowStub[ExchangeWorkflow](workflowId = orderId.toString)
      state <- ZWorkflowStub.query(
                 workflowStub.getExchangeOrderState()
               )
    } yield convertOrderState(state)

  def acceptOrder(orderId: UUID, buyerId: UUID): Task[Unit] =
    for {
      workflowStub <- client.newWorkflowStub[ExchangeWorkflow](workflowId = orderId.toString)
      _ <- ZWorkflowStub.signal(
             workflowStub.acceptExchangeOrder(
               AcceptExchangeOrderSignal(buyerId)
             )
           )
    } yield ()

  def buyerTransferConfirmation(orderId: UUID, screenshotUrl: String): Task[Unit] =
    for {
      workflowStub <- client.newWorkflowStub[ExchangeWorkflow](workflowId = orderId.toString)
      _ <- ZWorkflowStub.signal(
             workflowStub.buyerTransferConfirmation(
               BuyerConfirmationSignal(screenshotUrl)
             )
           )
    } yield ()

  def sellerTransferConfirmation(orderId: UUID): Task[Unit] =
    for {
      workflowStub <- client.newWorkflowStub[ExchangeWorkflow](workflowId = orderId.toString)
      _ <- ZWorkflowStub.signal(
             workflowStub.sellerTransferConfirmation()
           )
    } yield ()

  private def resultToOrder(orderView: ExchangeOrderResult): ExchangeOrder =
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

  private def convertOrderState(state: ExchangeOrderState): ExchangeOrderStatus =
    state.status.fromProto
}
