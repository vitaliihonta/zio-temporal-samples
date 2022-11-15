package com.cryptocrabs.workflows

import zio.temporal.*
import zio.temporal.workflow.*
import com.cryptocrabs.exchange.*
import org.slf4j.LoggerFactory

@workflowInterface
trait ExchangeWorkflow {
  @workflowMethod
  def exchangeOrder(order: ExchangeOrder): Unit

  @signalMethod
  def acceptExchangeOrder(): Unit

  @signalMethod
  def confirmFiatMoneyReceived(): Unit

  @queryMethod
  def transactionState(): Int
}

class ExchangeWorkflowImpl() extends ExchangeWorkflow {

  private val logger = LoggerFactory.getLogger(getClass)

  override def exchangeOrder(order: ExchangeOrder): Unit = {
    logger.info(s"Received $order")
    // TODO: implement
  }

  override def acceptExchangeOrder(): Unit = ???

  override def confirmFiatMoneyReceived(): Unit = ???

  override def transactionState(): Int = ???
}
