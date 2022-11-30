package com.cryptostock.workflows

import com.cryptostock.CryptoCurrency
import zio.*
import zio.temporal.*
import zio.temporal.workflow.*
import zio.temporal.state.*
import zio.temporal.saga.ZSaga
import org.slf4j.LoggerFactory
import com.cryptostock.exchange.*

import java.util.UUID
import ProtoConverters.given
import zio.temporal.protobuf.syntax.*

import java.time.Instant
import java.time.Duration as JDuration
import scala.util.Try

enum ExchangeOrderStateDetails(
  val isAccepted:          Boolean = false,
  val isConfirmedByBuyer:  Boolean = false,
  val isConfirmedBySeller: Boolean = false,
  val isStuck: Boolean = false) {

  case Created
  case Cancelled
  case Accepted(buyerId: UUID) extends ExchangeOrderStateDetails(isAccepted = true)
  case FoundsHeld(buyerId: UUID)
  case ConfirmedByBuyer(buyerId: UUID, screenshotUrl: String)
      extends ExchangeOrderStateDetails(isConfirmedByBuyer = true)

  case ConfirmedBySeller(buyerId: UUID, screenshotUrl: String)
      extends ExchangeOrderStateDetails(isConfirmedBySeller = true)

  case Completed(buyerId: UUID, screenshotUrl: String)
  case Stuck(buyerId: UUID, screenshotUrl: String) extends ExchangeOrderStateDetails(isStuck = true)
}

case class ExchangeOrderState(
  id:       UUID,
  sellerId: UUID,
  amount:   BigDecimal,
  currency: CryptoCurrency,
  details:  ExchangeOrderStateDetails)

class ExchangeWorkflowImpl() extends ExchangeWorkflow {

  private val logger  = LoggerFactory.getLogger(getClass)
  private val orderId = UUID.fromString(ZWorkflow.info.workflowId)

  private val exchangeActivity: ExchangeOrderActivity = ZWorkflow
    .newActivityStub[ExchangeOrderActivity]
    .withStartToCloseTimeout(5.seconds)
    .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(3))
    .build

  private val orderState = ZWorkflowState.empty[ExchangeOrderState]

  override def exchangeOrder(orderRequest: ExchangeOrderRequest): ExchangeOrderView = {

    val result = for {
      _             <- putOrder(orderRequest)
      buyerId       <- waitUntilAcceptedOrCancelByTimeout()
      _             <- holdFunds(buyerId)
      screenshotUrl <- waitForBuyerConfirmationOrCancel()
      _             <- waitForSellerConfirmationOrFail(buyerId, screenshotUrl)
      _             <- transferFunds(buyerId, screenshotUrl)
    } yield exchangeOrderState()

    result.run().merge
  }

  override def acceptExchangeOrder(accepted: AcceptExchangeOrderSignal): Unit = {
    logger.info("Order accepted!")
    orderState.update(
      _.copy(details = ExchangeOrderStateDetails.Accepted(accepted.buyerId.fromProto))
    )
  }

  override def buyerTransferConfirmation(confirmed: BuyerConfirmationSignal): Unit = {
    orderState.updateWhen {
      case state @ ExchangeOrderState(_, _, _, _, ExchangeOrderStateDetails.FoundsHeld(buyerId)) =>
        logger.info("Buyer confirmation received!")
        state.copy(details = ExchangeOrderStateDetails.ConfirmedByBuyer(buyerId, confirmed.screenshotUrl))
    }
  }

  override def sellerTransferConfirmation(): Unit = {
    orderState.updateWhen {
      case state @ ExchangeOrderState(_, _, _, _, ExchangeOrderStateDetails.ConfirmedByBuyer(buyerId, screenshotUrl)) =>
        logger.info("Seller confirmation received!")
        state.copy(details = ExchangeOrderStateDetails.ConfirmedBySeller(buyerId, screenshotUrl))
    }
  }

  override def getExchangeOrderState(): ExchangeOrderView =
    exchangeOrderState()

  // This allows to avoid using `return`
  private type Result[+A] = ZSaga[ExchangeOrderView, A]
  private def finishWorkflow: Result[Nothing] = ZSaga.fail(exchangeOrderState())

  private def putOrder(orderRequest: ExchangeOrderRequest): Result[Unit] = {
    logger.info(s"Received order id=$orderId")

    exchangeActivity.putExchangeOrder(orderId, orderRequest)

    orderState := ExchangeOrderState(
      id = orderId,
      sellerId = orderRequest.seller.fromProto,
      amount = orderRequest.amount.fromProto,
      currency = orderRequest.currency.fromProto,
      details = ExchangeOrderStateDetails.Created
    )

    ZSaga.succeed(())
  }
  // Returns buyerId
  private def waitUntilAcceptedOrCancelByTimeout(): Result[UUID] = {
    logger.info("Waiting for order to be accepted")
    ZWorkflow.awaitUntil(5.seconds)(orderState.snapshot.details.isAccepted)
    orderState.snapshot.details match {
      case ExchangeOrderStateDetails.Accepted(buyerId) =>
        logger.info("Order accepted")
        exchangeActivity.orderAccepted(orderId, buyerId)
        ZSaga.succeed(buyerId)

      case other =>
        logger.info(s"Order cancelled by timeout $other")
        orderState.update(
          _.copy(details = ExchangeOrderStateDetails.Cancelled)
        )
        finishWorkflow
    }
  }

  private def holdFunds(buyerId: UUID): Result[Unit] = {
    val hold = ZSaga
      .make(
        Try(exchangeActivity.holdCryptoFunds(orderId)).toEither
      )(compensate = {
        // Stuck orders should be manually resolved
        if (!orderState.snapshot.details.isStuck) {
          exchangeActivity.releaseCryptoFunds(orderId)
        }
      })
      .mapError(_ => exchangeOrderState())

    hold.map { _ =>
      orderState.update(
        _.copy(
          details = ExchangeOrderStateDetails.FoundsHeld(buyerId)
        )
      )
    }
  }

  private def waitForBuyerConfirmationOrCancel(): Result[String] = {
    logger.info("Waiting for buyer confirmation...")
    ZWorkflow.awaitUntil(30.seconds)(orderState.snapshot.details.isConfirmedByBuyer)

    orderState.snapshot.details match {
      case ExchangeOrderStateDetails.ConfirmedByBuyer(buyerId, screenshotUrl) =>
        logger.info("Buyer confirmed")
        exchangeActivity.showBuyerConfirmation(orderId, screenshotUrl)
        ZSaga.succeed(screenshotUrl)
      case _ =>
        logger.info("Waiting too long for buyer confirmation")
        orderState.update(
          _.copy(
            details = ExchangeOrderStateDetails.Cancelled
          )
        )
        finishWorkflow
    }
  }

  private def waitForSellerConfirmationOrFail(buyerId: UUID, screenshotUrl: String): Result[Unit] = {
    logger.info("Waiting for seller confirmation...")
    ZWorkflow.awaitUntil(30.seconds)(orderState.snapshot.details.isConfirmedBySeller)
    orderState.snapshot.details match {
      case ExchangeOrderStateDetails.ConfirmedBySeller(buyerId, screenshotUrl) =>
        logger.info("Seller confirmed")
        ZSaga.succeed(())
      case _ =>
        logger.info("Received no confirmation from buyer, exchange stuck")
        orderState.update(
          _.copy(
            details = ExchangeOrderStateDetails.Stuck(buyerId, screenshotUrl)
          )
        )
        finishWorkflow
    }
  }

  private def transferFunds(buyerId: UUID, screenshotUrl: String): Result[Unit] = {
    ZSaga
      .effect(exchangeActivity.transferCryptoFunds(orderId))
      .mapError(_ => exchangeOrderState())
      .map { _ =>
        logger.info("Crypto transferred")
        orderState.update(
          _.copy(
            details = ExchangeOrderStateDetails.Completed(buyerId, screenshotUrl)
          )
        )
      }
  }

  private def exchangeOrderState(): ExchangeOrderView = {
    orderState.toOption
      .map { state =>

        val (status, buyerInfo) = state.details match {
          case ExchangeOrderStateDetails.Created   => ExchangeOrderStatus.Created   -> None
          case ExchangeOrderStateDetails.Cancelled => ExchangeOrderStatus.Cancelled -> None
          case ExchangeOrderStateDetails.Accepted(buyerId) =>
            ExchangeOrderStatus.Accepted -> Some(ExchangeOrderBuyerInfo(buyerId, None))
          case ExchangeOrderStateDetails.FoundsHeld(buyerId) =>
            ExchangeOrderStatus.FoundsHeld -> Some(ExchangeOrderBuyerInfo(buyerId, None))
          case ExchangeOrderStateDetails.ConfirmedByBuyer(buyerId, screenshotUrl) =>
            ExchangeOrderStatus.ConfirmedByBuyer -> Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))
          case ExchangeOrderStateDetails.ConfirmedBySeller(buyerId, screenshotUrl) =>
            ExchangeOrderStatus.ConfirmedBySeller -> Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))
          case ExchangeOrderStateDetails.Completed(buyerId, screenshotUrl) =>
            ExchangeOrderStatus.Completed -> Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))
          case ExchangeOrderStateDetails.Stuck(buyerId, screenshotUrl) =>
            ExchangeOrderStatus.Stuck -> Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))
        }

        ExchangeOrderView(
          id = state.id,
          seller = Some(state.sellerId),
          amount = Some(state.amount),
          currency = Some(state.currency),
          status = status,
          buyerInfo = buyerInfo
        )
      }
      // Handling cases when workflow haven't started yet
      .getOrElse(
        ExchangeOrderView(
          id = orderId,
          seller = None,
          amount = None,
          currency = None,
          status = ExchangeOrderStatus.Placed,
          buyerInfo = None
        )
      )
  }
}
