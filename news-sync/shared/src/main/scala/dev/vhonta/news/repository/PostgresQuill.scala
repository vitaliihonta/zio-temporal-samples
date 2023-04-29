package dev.vhonta.news.repository

import io.getquill.{NamingStrategy, SnakeCase}
import io.getquill.jdbczio.Quill
import zio._

import javax.sql.DataSource

class PostgresQuill[+N <: NamingStrategy](override val naming: N, override val ds: DataSource)
    extends Quill.Postgres[N](naming, ds)
    with PostgresCirceJsonExtensions

object PostgresQuill {
  val make: URLayer[javax.sql.DataSource, PostgresQuill[SnakeCase]] =
    ZLayer.fromFunction((ds: javax.sql.DataSource) => new PostgresQuill[SnakeCase](SnakeCase, ds))
}
