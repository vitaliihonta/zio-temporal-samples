package dev.vhonta.news.repository

import io.getquill.context.jdbc.{Decoders, Encoders}
import io.getquill.context.json.PostgresJsonExtensions
import io.circe.syntax._
import java.sql.Types
import scala.reflect.{ClassTag, classTag}

trait PostgresCirceJsonExtensions { this: Encoders with Decoders with PostgresJsonExtensions =>
  private val jsonType = "jsonb"
  def circeEncoder[A: io.circe.Encoder.AsObject]: Encoder[A] =
    encoder(
      Types.VARCHAR,
      (index, value, row) => {
        val obj = new org.postgresql.util.PGobject()
        obj.setType(jsonType)
        val jsonString = value.asJson.noSpaces
        obj.setValue(jsonString)
        row.setObject(index, obj)
      }
    )

  def circeDecoder[A: io.circe.Decoder: ClassTag]: Decoder[A] =
    decoder((index, row, session) => {
      val obj        = row.getObject(index, classOf[org.postgresql.util.PGobject])
      val jsonString = obj.getValue
      io.circe.parser.decode[A](jsonString) match {
        case Right(value) => value
        case Left(error) =>
          throw new IllegalArgumentException(
            s"Error decoding the Json value '$jsonString' into a ${classTag[A]}. Message: $error"
          )
      }
    })
}
