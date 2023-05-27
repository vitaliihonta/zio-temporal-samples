package dev.vhonta.content

import io.circe.Codec
import io.circe.generic.extras.Configuration
import enumeratum.values

package object newsapi {
  implicit val circeConfiguration: Configuration = Configuration.default
  implicit val sortByCodec: Codec[SortBy] =
    Codec.from(values.Circe.decoder(SortBy), values.Circe.encoder(SortBy))

}
