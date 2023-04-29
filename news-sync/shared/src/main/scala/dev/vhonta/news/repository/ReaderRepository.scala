package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.Reader
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill

import java.sql.SQLException
import java.util.UUID

object ReaderRepository {
  val make: URLayer[PostgresQuill[SnakeCase], ReaderRepository] =
    ZLayer.fromFunction(ReaderRepository(_: PostgresQuill[SnakeCase]))
}

case class ReaderRepository(quill: PostgresQuill[SnakeCase]) {
  import quill._

  def create(reader: Reader): IO[SQLException, Reader] = {
    val insert = quote {
      query[Reader].insertValue(lift(reader))
    }
    run(insert).as(reader)
  }

  def findById(readerId: UUID): IO[SQLException, Option[Reader]] = {
    val insert = quote {
      query[Reader]
        .filter(_.id == lift(readerId))
        .take(1)
    }
    run(insert).map(_.headOption)
  }
}
