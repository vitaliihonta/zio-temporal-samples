package com.cryptostock.workflows

import com.cryptostock.exchange.*
import zio.*
import zio.temporal.*
import zio.temporal.activity.*
import zio.temporal.protobuf
import zio.temporal.protobuf.syntax.*
import ProtoConverters.given

// accepts only protobuf-generated messages (not enums!)
@activityInterface
trait ExchangeOrderActivity {
  def putExchangeOrder(id: protobuf.UUID, request: ExchangeOrderRequest): Unit

  def orderAccepted(orderId: protobuf.UUID, buyerId: protobuf.UUID): Unit

  def holdCryptoFunds(orderId: protobuf.UUID): Unit

  def releaseCryptoFunds(orderId: protobuf.UUID): Unit

  def showBuyerConfirmation(orderId: protobuf.UUID, screenshotUrl: String): Unit

  def transferCryptoFunds(orderId: protobuf.UUID): Unit
}

object ExchangeOrderActivityImpl {
  val make: URLayer[ZActivityOptions[Any], ExchangeOrderActivity] =
    ZLayer.fromFunction(new ExchangeOrderActivityImpl()(using _))
}

// TODO: think about adding something like DAO layer imitation
class ExchangeOrderActivityImpl()(using ZActivityOptions[Any]) extends ExchangeOrderActivity {

  override def putExchangeOrder(id: protobuf.UUID, request: ExchangeOrderRequest): Unit =
    ZActivity.run {
      ZIO.logInfo(
        s"Hey, who wanna buy ${request.amount.fromProto} ${request.currency.fromProto}s? Order id is ${id.fromProto}, seller is ${request.seller.fromProto}"
      )
    }

  override def orderAccepted(orderId: protobuf.UUID, buyerId: protobuf.UUID): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Order ${orderId.fromProto} accepted by ${buyerId.fromProto}")
    }
  }

  override def holdCryptoFunds(orderId: protobuf.UUID): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Order ${orderId.fromProto} amount held for the buyer")
    }
  }

  override def releaseCryptoFunds(orderId: protobuf.UUID): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Order ${orderId.fromProto} cancelled, founds released")
    }
  }

  override def showBuyerConfirmation(orderId: protobuf.UUID, screenshotUrl: String): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Order ${orderId.fromProto} payed by buyer screenshot=$screenshotUrl")
    }
  }

  override def transferCryptoFunds(orderId: protobuf.UUID): Unit = {
    ZActivity.run {
      ZIO.logInfo(s"Order ${orderId.fromProto} complete")
    }
  }
}
