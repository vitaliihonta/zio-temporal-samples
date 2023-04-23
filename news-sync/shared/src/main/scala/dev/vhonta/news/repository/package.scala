package dev.vhonta.news

import enumeratum.{Enum, EnumEntry}
import io.getquill.MappedEncoding

package object repository {
  implicit def stringEnumEncoder[E <: EnumEntry]: MappedEncoding[E, String] =
    MappedEncoding(_.entryName)

  implicit def stringEnumDecoder[E <: EnumEntry](implicit e: Enum[E]): MappedEncoding[String, E] =
    MappedEncoding(e.withNameInsensitive)
}
