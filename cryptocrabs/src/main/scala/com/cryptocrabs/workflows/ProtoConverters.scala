package com.cryptocrabs.workflows

import com.cryptocrabs.{CryptoCurrency, ExchangeOrderStatus}
import com.cryptocrabs.{exchange => proto}
import zio.temporal.protobuf.{EnumProtoType, ProtoType}

object ProtoConverters {
  given ProtoType.Of[CryptoCurrency, proto.CryptoCurrency] = EnumProtoType(proto.CryptoCurrency).to[CryptoCurrency]

  given ProtoType.Of[ExchangeOrderStatus, proto.ExchangeOrderStatus] =
    EnumProtoType(proto.ExchangeOrderStatus).to[ExchangeOrderStatus]

}
