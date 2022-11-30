package com.cryptostock.workflows

import zio._
import zio.temporal.*
import zio.temporal.workflow.*
import com.cryptostock.exchange.*
import org.slf4j.{LoggerFactory, MDC}

@workflowInterface
trait ExchangeWorkflow {
  @workflowMethod
  def exchangeOrder(order: ExchangeOrderRequest): ExchangeOrderView

  @signalMethod
  def acceptExchangeOrder(accepted: AcceptExchangeOrderSignal): Unit

  @signalMethod
  def buyerTransferConfirmation(confirmed: BuyerConfirmationSignal): Unit

  @signalMethod
  def sellerTransferConfirmation(): Unit

  @queryMethod
  def getExchangeOrderState(): ExchangeOrderView
}
