package com.cryptostock.workflows

import com.cryptostock.{CryptoCurrency, ExchangeOrderStatus}
import com.cryptostock.{exchange => proto}
import zio.temporal.protobuf.{EnumProtoType, ProtoType}

object ProtoConverters {
  given ProtoType.Of[CryptoCurrency, proto.CryptoCurrency] = EnumProtoType(proto.CryptoCurrency).to[CryptoCurrency]

  given ProtoType.Of[ExchangeOrderStatus, proto.ExchangeOrderStatus] =
    EnumProtoType(proto.ExchangeOrderStatus).to[ExchangeOrderStatus]

}
