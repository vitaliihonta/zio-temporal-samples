package com.cryptostock.workflows

import com.cryptostock.CryptoCurrency
import com.cryptostock.exchange.*
import zio.*
import zio.temporal.*
import zio.temporal.workflow.*
import zio.temporal.state.*
import zio.temporal.activity.*
import zio.temporal.protobuf.syntax.*
import ProtoConverters.given
import java.util.UUID
import scala.util.control.NoStackTrace

enum StateDetails(
  val status: ExchangeOrderStatus) {

  case Created extends StateDetails(ExchangeOrderStatus.Created)

  case Cancelled extends StateDetails(ExchangeOrderStatus.Cancelled)

  case Accepted(buyerId: UUID) extends StateDetails(ExchangeOrderStatus.Accepted)

  case FoundsHeld(buyerId: UUID) extends StateDetails(ExchangeOrderStatus.FoundsHeld)

  case ConfirmedByBuyer(buyerId: UUID, screenshotUrl: String) extends StateDetails(ExchangeOrderStatus.ConfirmedByBuyer)

  case ConfirmedBySeller(buyerId: UUID, screenshotUrl: String)
      extends StateDetails(ExchangeOrderStatus.ConfirmedBySeller)

  case Completed(buyerId: UUID, screenshotUrl: String) extends StateDetails(ExchangeOrderStatus.Completed)

  case Stuck(buyerId: UUID, screenshotUrl: String) extends StateDetails(ExchangeOrderStatus.Stuck)
}

case class ExchangeStateInternal(
  id:       UUID,
  sellerId: UUID,
  amount:   BigDecimal,
  currency: CryptoCurrency,
  details:  StateDetails)

// Exceptions that we might handle
sealed abstract class BusinessException(msg: String) extends Exception(msg) with NoStackTrace

case object OrderNotAcceptedTimeout extends BusinessException("Timed out: Order not accepted")

case object BuyerNotConfirmTimeout extends BusinessException("Timed out: Buyer didn't confirm")
case object OrderStuckTimeout      extends BusinessException("Timed out: Order not confirmed")

class ExchangeWorkflowImpl() extends ExchangeWorkflow {

  private val logger  = ZWorkflow.makeLogger
  private val orderId = UUID.fromString(ZWorkflow.info.workflowId)

  private val exchangeActivity: ZActivityStub.Of[ExchangeOrderActivity] =
    ZWorkflow.newActivityStub[ExchangeOrderActivity](
      ZActivityOptions
        .withStartToCloseTimeout(5.seconds)
        .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(3))
    )

  private val orderState = ZWorkflowState.empty[ExchangeStateInternal]

  override def exchangeOrder(orderRequest: ExchangeOrderRequest): ExchangeOrderResult = {

    val saga: ZSaga[Unit] = for {
      _             <- putOrder(orderRequest)
      buyerId       <- waitUntilAcceptedOrCancelByTimeout()
      _             <- holdFunds(buyerId)
      screenshotUrl <- waitForBuyerConfirmationOrCancel()
      _             <- waitForSellerConfirmationOrFail(buyerId, screenshotUrl)
      _             <- transferFunds(buyerId, screenshotUrl)
    } yield ()

    try {
      saga.runOrThrow()
    } catch {
      // Handle business exceptions (they rollback the saga but do not retry the workflow)
      case e: BusinessException =>
        logger.info("Transaction failed", e)
        failExchangeOrder(e)
    }

    stateToResult()
  }

  override def acceptExchangeOrder(accepted: AcceptExchangeOrderSignal): Unit = {
    logger.info("Order accepted!")
    orderState.update(
      _.copy(details = StateDetails.Accepted(accepted.buyerId.fromProto))
    )
  }

  override def buyerTransferConfirmation(confirmed: BuyerConfirmationSignal): Unit = {
    orderState.updateWhen { case state @ ExchangeStateInternal(_, _, _, _, StateDetails.FoundsHeld(buyerId)) =>
      logger.info("Buyer confirmation received!")
      state.copy(details = StateDetails.ConfirmedByBuyer(buyerId, confirmed.screenshotUrl))
    }
  }

  override def sellerTransferConfirmation(): Unit = {
    orderState.updateWhen {
      case state @ ExchangeStateInternal(_, _, _, _, StateDetails.ConfirmedByBuyer(buyerId, screenshotUrl)) =>
        logger.info("Seller confirmation received!")
        state.copy(details = StateDetails.ConfirmedBySeller(buyerId, screenshotUrl))
    }
  }

  override def getExchangeOrderState(): ExchangeOrderState = {
    val status = orderState.toOption
      .map(_.details.status)
      .getOrElse(
        ExchangeOrderStatus.Created
      )

    ExchangeOrderState(status)
  }

  private def putOrder(orderRequest: ExchangeOrderRequest): ZSaga[Unit] = ZSaga.attempt {
    logger.info(s"Received order id=$orderId")

    ZActivityStub.execute(
      exchangeActivity.putExchangeOrder(orderId, orderRequest)
    )

    orderState := ExchangeStateInternal(
      id = orderId,
      sellerId = orderRequest.seller.fromProto,
      amount = orderRequest.amount.fromProto,
      currency = orderRequest.currency.fromProto,
      details = StateDetails.Created
    )
  }
  // Returns buyerId
  private def waitUntilAcceptedOrCancelByTimeout(): ZSaga[UUID] = {
    logger.info("Waiting for order to be accepted")

    ZWorkflow.awaitUntil(5.seconds)(orderState.snapshot.details.status.isAccepted)

    orderState.snapshot.details match {
      case StateDetails.Accepted(buyerId) =>
        logger.info("Order accepted")
        ZSaga.attempt {
          ZActivityStub.execute(
            exchangeActivity.orderAccepted(orderId, buyerId)
          )
          buyerId
        }

      case other =>
        logger.info(s"Order cancelled by timeout $other")
        orderState.update(
          _.copy(details = StateDetails.Cancelled)
        )
        ZSaga.fail(OrderNotAcceptedTimeout)
    }
  }

  private def holdFunds(buyerId: UUID): ZSaga[Unit] = {
    for {
      _ <- ZSaga.attempt(
             ZActivityStub.execute(
               exchangeActivity.holdCryptoFunds(orderId)
             )
           )
      _ <- ZSaga.compensation(
             // Stuck orders should be manually resolved
             if (!orderState.snapshot.details.status.isStuck) {
               ZActivityStub.execute(
                 exchangeActivity.releaseCryptoFunds(orderId)
               )
             }
           )
    } yield {
      orderState.update(
        _.copy(
          details = StateDetails.FoundsHeld(buyerId)
        )
      )
    }
  }

  private def waitForBuyerConfirmationOrCancel(): ZSaga[String] = {
    logger.info("Waiting for buyer confirmation...")

    ZWorkflow.awaitUntil(30.seconds)(orderState.snapshot.details.status.isConfirmedByBuyer)

    orderState.snapshot.details match {
      case StateDetails.ConfirmedByBuyer(buyerId, screenshotUrl) =>
        logger.info("Buyer confirmed")
        ZSaga.attempt {
          ZActivityStub.execute(
            exchangeActivity.showBuyerConfirmation(orderId, screenshotUrl)
          )

          screenshotUrl
        }
      case _ =>
        logger.info("Waiting too long for buyer confirmation")
        ZSaga.fail(BuyerNotConfirmTimeout)
    }
  }

  private def waitForSellerConfirmationOrFail(buyerId: UUID, screenshotUrl: String): ZSaga[Unit] = {
    logger.info("Waiting for seller confirmation...")

    ZWorkflow.awaitUntil(30.seconds)(
      orderState.snapshot.details.status.isConfirmedBySeller
    )

    orderState.snapshot.details match {
      case StateDetails.ConfirmedBySeller(_, _) =>
        logger.info("Seller confirmed")
        ZSaga.unit
      case _ =>
        logger.info("Received no confirmation from buyer, exchange stuck")
        ZSaga.fail(OrderStuckTimeout)
    }
  }

  private def transferFunds(buyerId: UUID, screenshotUrl: String): ZSaga[Unit] = {
    ZSaga
      .attempt(
        ZActivityStub.execute(
          exchangeActivity.transferCryptoFunds(orderId)
        )
      )
      .as {
        logger.info("Crypto transferred")
        orderState.update(
          _.copy(
            details = StateDetails.Completed(buyerId, screenshotUrl)
          )
        )
      }
  }

  private def stateToResult(): ExchangeOrderResult = {
    val state = orderState.snapshot

    val buyerInfo = state.details match {
      case StateDetails.Created | StateDetails.Cancelled => None

      case StateDetails.Accepted(buyerId) =>
        Some(ExchangeOrderBuyerInfo(buyerId, None))

      case StateDetails.FoundsHeld(buyerId) =>
        Some(ExchangeOrderBuyerInfo(buyerId, None))

      case StateDetails.ConfirmedByBuyer(buyerId, screenshotUrl) =>
        Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))

      case StateDetails.ConfirmedBySeller(buyerId, screenshotUrl) =>
        Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))

      case StateDetails.Completed(buyerId, screenshotUrl) =>
        Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))

      case StateDetails.Stuck(buyerId, screenshotUrl) =>
        Some(ExchangeOrderBuyerInfo(buyerId, Some(screenshotUrl)))
    }

    ExchangeOrderResult(
      id = state.id,
      seller = Some(state.sellerId),
      amount = Some(state.amount),
      currency = Some(state.currency),
      status = state.details.status,
      buyerInfo = buyerInfo
    )
  }

  private def failExchangeOrder(e: BusinessException): Unit = e match {
    case OrderNotAcceptedTimeout | BuyerNotConfirmTimeout =>
      orderState.update(
        _.copy(
          details = StateDetails.Cancelled
        )
      )
    case OrderStuckTimeout =>
      orderState.updateWhen {
        case state @ ExchangeStateInternal(_, _, _, _, StateDetails.ConfirmedByBuyer(buyerId, screenshotUrl)) =>
          state.copy(
            details = StateDetails.Stuck(buyerId, screenshotUrl)
          )
      }
  }
}
