package com.cryptostock

import java.util.UUID

enum CryptoCurrency {
  case CrabsCoin, Garyeum, Snaily
}

enum ExchangeOrderStatus(val isFinal: Boolean = false) {
  case Created, Placed, Accepted, FoundsHeld, ConfirmedByBuyer, ConfirmedBySeller
  case Cancelled extends ExchangeOrderStatus(true)
  case Stuck     extends ExchangeOrderStatus(true)
  case Completed extends ExchangeOrderStatus(true)
}

case class ExchangeOrderBuyer(
  id:            UUID,
  screenshotUrl: Option[String])

case class ExchangeOrder(
  orderId:   UUID,
  sellerId:  Option[UUID],
  amount:    Option[BigDecimal],
  currency:  Option[CryptoCurrency],
  status:    ExchangeOrderStatus,
  buyerInfo: Option[ExchangeOrderBuyer])
