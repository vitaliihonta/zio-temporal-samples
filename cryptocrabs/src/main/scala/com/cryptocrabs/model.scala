package com.cryptocrabs

import java.util.UUID

enum CryptoCurrency {
  case CrabsCoin, Garyeum, Snaily
}

enum ExchangeOrderStatus {
  case Created, Placed, Cancelled, Accepted, FoundsHeld, ConfirmedByBuyer, ConfirmedBySeller, Stuck, Completed
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
