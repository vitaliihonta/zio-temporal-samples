package dev.vhonta.ukvi.visa

import zio.json._
import enumeratum.{Enum, EnumEntry}
import enumeratum.values.{StringEnum, StringEnumEntry}
object EnumeratumZioJson {
  implicit def enumCodec[E <: EnumEntry](implicit e: Enum[E]): JsonCodec[E] =
    JsonCodec[E](
      JsonEncoder.string.contramap((_: E).entryName),
      JsonDecoder.string.mapOrFail(e.withNameEither(_).left.map(_.getMessage))
    )

  implicit def stringEnumCodec[E <: StringEnumEntry](implicit e: StringEnum[E]): JsonCodec[E] =
    JsonCodec[E](
      JsonEncoder.string.contramap((_: E).value),
      JsonDecoder.string.mapOrFail(e.withValueEither(_).left.map(_.getMessage))
    )
}
