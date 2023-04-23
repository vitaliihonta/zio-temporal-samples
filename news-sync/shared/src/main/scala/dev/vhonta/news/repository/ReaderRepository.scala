package dev.vhonta.news.repository

import zio._
import dev.vhonta.news.Reader
import io.getquill.SnakeCase
import io.getquill.jdbczio.Quill
import java.sql.SQLException

object ReaderRepository {
  val make: URLayer[Quill.Postgres[SnakeCase], ReaderRepository] =
    ZLayer.fromFunction(ReaderRepository(_: Quill.Postgres[SnakeCase]))
}

case class ReaderRepository(quill: Quill.Postgres[SnakeCase]) {
  import quill._

  def create(reader: Reader): IO[SQLException, Reader] = {
    val insert = quote {
      query[Reader].insertValue(lift(reader))
    }
    run(insert).as(reader)
  }
}
