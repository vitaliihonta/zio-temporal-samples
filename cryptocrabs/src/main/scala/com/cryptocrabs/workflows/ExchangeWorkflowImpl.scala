package com.cryptocrabs.workflows

import com.cryptocrabs.CryptoCurrency
import zio.*
import zio.temporal.*
import zio.temporal.workflow.*
import zio.temporal.state.*
import org.slf4j.LoggerFactory
import com.cryptocrabs.exchange.*
import java.util.UUID
import ProtoConverters.given
import zio.temporal.protobuf.syntax.*
import java.time.Instant
import java.time.{Duration => JDuration}

enum ExchangeOrderStateDetails(
  val isAccepted:         Boolean = false,
  val isConfirmedByBuyer: Boolean = false,
  val isConfirmedBySeller: Boolean = false) {

  case Created
  case Cancelled
  case Accepted(buyerId: UUID) extends ExchangeOrderStateDetails(isAccepted = true)
  case FoundsHeld(buyerId: UUID)
  case ConfirmedByBuyer(buyerId: UUID, screenshotUrl: String)
      extends ExchangeOrderStateDetails(isConfirmedByBuyer = true)

  case ConfirmedBySeller(buyerId: UUID, screenshotUrl: String)
      extends ExchangeOrderStateDetails(isConfirmedBySeller = true)

  case Completed(buyerId: UUID, screenshotUrl: String)
  case Stuck(buyerId: UUID, screenshotUrl: String)
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

  private val exchangeActivity = ZWorkflow
    .newActivityStub[ExchangeOrderActivity]
    .withStartToCloseTimeout(5.seconds)
    .withRetryOptions(ZRetryOptions.default.withMaximumAttempts(3))
    .build

  private val orderState = ZWorkflowState.empty[ExchangeOrderState]

  override def exchangeOrder(orderRequest: ExchangeOrderRequest): ExchangeOrderView = {

    logger.info(s"Received order id=$orderId")

    exchangeActivity.placeExchangeOrder(orderId, orderRequest)

    orderState := ExchangeOrderState(
      id = orderId,
      sellerId = orderRequest.seller.fromProto,
      amount = orderRequest.amount.fromProto,
      currency = orderRequest.currency.fromProto,
      details = ExchangeOrderStateDetails.Created
    )

    logger.info("Waiting for order to be accepted")
    ZWorkflow.awaitUntil(30.seconds) {
      val state = orderState.snapshot
      logger.info(s"Wait state=$state")
      state.details.isAccepted
    }

    logger.info(s"Waiting finished ${orderState}")
//    logger.info(s"Waiting finished with state=${orderState.snapshot}")
    // timeout occurred
    orderState.snapshot.details match {
      case ExchangeOrderStateDetails.Accepted(buyerId) =>
        logger.info("Order accepted")
        orderState.update(
          _.copy(
            details = ExchangeOrderStateDetails.Accepted(buyerId)
          )
        )

        exchangeActivity.holdCrypto(orderId)
        orderState.update(
          _.copy(
            details = ExchangeOrderStateDetails.FoundsHeld(buyerId)
          )
        )

        logger.info("Waiting for seller confirmation...")
        ZWorkflow.awaitUntil(30.seconds)(orderState.snapshot.details.isConfirmedByBuyer)

        orderState.snapshot.details match {
          case ExchangeOrderStateDetails.ConfirmedByBuyer(buyerId, screenshotUrl) =>
            logger.info("Seller confirmed")
            orderState.update(
              _.copy(
                details = ExchangeOrderStateDetails.ConfirmedByBuyer(buyerId, screenshotUrl)
              )
            )

            logger.info("Waiting for buyer confirmation...")

            ZWorkflow.awaitUntil(30.seconds)(orderState.snapshot.details.isConfirmedByBuyer)
            orderState.snapshot.details match {
              case ExchangeOrderStateDetails.ConfirmedBySeller(buyerId, screenshotUrl) =>
                logger.info("Buyer confirmed")
                orderState.update(
                  _.copy(
                    details = ExchangeOrderStateDetails.ConfirmedBySeller(buyerId, screenshotUrl)
                  )
                )
                exchangeActivity.transferCrypto(orderId)
                logger.info("Crypto transferred")
                orderState.update(
                  _.copy(
                    details = ExchangeOrderStateDetails.Completed(buyerId, screenshotUrl)
                  )
                )
                stateToView()

              case _ =>
                logger.info("Received no confirmation from buyer, exchange stuck")
                orderState.update(
                  _.copy(
                    details = ExchangeOrderStateDetails.Stuck(buyerId, screenshotUrl)
                  )
                )
                stateToView()
            }

          case _ =>
            logger.info("Waiting too long for buyer confirmation")
            orderState.update(
              _.copy(
                details = ExchangeOrderStateDetails.Cancelled
              )
            )
            // TODO: unhold money
            stateToView()
        }

      case _ =>
        logger.info("Order cancelled by timeout")
        orderState.update(
          _.copy(
            details = ExchangeOrderStateDetails.Cancelled
          )
        )
        stateToView()
    }
  }

  override def acceptExchangeOrder(accepted: AcceptExchangeOrderSignal): Unit = ???

  override def buyerTransferConfirmation(confirmed: BuyerConfirmationSignal): Unit = ???

  override def sellerTransferConfirmation(): Unit = ???

  override def transactionState(): ExchangeOrderView =
    stateToView()

  private def stateToView(): ExchangeOrderView = {
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
