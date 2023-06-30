package dev.vhonta.content

import zio.json._

package object newsapi {
  implicit val sortByCodec: JsonCodec[SortBy] = {
    JsonCodec(
      JsonEncoder.string.contramap[SortBy](_.value),
      JsonDecoder.string.mapOrFail(SortBy.withValueEither(_).left.map(_.getMessage))
    )
  }

}
