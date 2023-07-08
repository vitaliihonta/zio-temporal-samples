package dev.vhonta.content.puller.workflows.storage

import com.github.mjakubowski84.parquet4s._
import dev.vhonta.content.ContentType
import java.util.UUID

object ParquetSerializers {
  implicit val contentTypeParquetCodec: OptionalValueCodec[ContentType] =
    new OptionalValueCodec[ContentType] {
      override protected def encodeNonNull(data: ContentType, configuration: ValueCodecConfiguration): Value =
        ValueEncoder.stringEncoder.encode(data.entryName, configuration)

      override protected def decodeNonNull(value: Value, configuration: ValueCodecConfiguration): ContentType =
        ContentType.withName(
          ValueDecoder.stringDecoder.decode(value, configuration)
        )
    }

  implicit val contentTypeSchema: TypedSchemaDef[ContentType] =
    TypedSchemaDef.stringSchema.typed[ContentType]

  implicit val uuidParquetCodec: OptionalValueCodec[UUID] =
    new OptionalValueCodec[UUID] {
      override protected def encodeNonNull(data: UUID, configuration: ValueCodecConfiguration): Value =
        ValueEncoder.stringEncoder.encode(data.toString, configuration)

      override protected def decodeNonNull(value: Value, configuration: ValueCodecConfiguration): UUID =
        UUID.fromString(
          ValueDecoder.stringDecoder.decode(value, configuration)
        )
    }

  implicit val uuidSchema: TypedSchemaDef[UUID] =
    TypedSchemaDef.stringSchema.typed[UUID]
}
