package dev.vhonta.content

import enumeratum.{Enum, EnumEntry}
import java.util.UUID

sealed abstract class ContentLanguage(val code: String) extends EnumEntry
object ContentLanguage extends Enum[ContentLanguage] {
  case object English   extends ContentLanguage("en")
  case object French    extends ContentLanguage("fr")
  case object Spanish   extends ContentLanguage("es")
  case object Ukrainian extends ContentLanguage("uk")

  override val values = findValues
}

case class ContentFeedTopic(
  id:    UUID,
  owner: UUID, /*subscriber ID*/
  topic: String,
  lang:  ContentLanguage)

sealed trait ContentType extends EnumEntry
case object ContentType extends Enum[ContentType] {
  case object Text  extends ContentType
  case object Video extends ContentType

  override val values = findValues
}
