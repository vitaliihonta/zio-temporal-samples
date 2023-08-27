package dev.vhonta.content.repository

import dev.vhonta.content.{ContentFeedIntegrationType, PullerState}
import io.getquill.{Query, SnakeCase}
import zio._

import java.sql.SQLException

object PullerStateRepository {
  val make: URLayer[PostgresQuill[SnakeCase], PullerStateRepository] =
    ZLayer.fromFunction(PullerStateRepository(_: PostgresQuill[SnakeCase]))
}

case class PullerStateRepository(quill: PostgresQuill[SnakeCase]) {

  import quill._

  def upsertStates(states: List[PullerState]): IO[SQLException, Unit] = {
    val upsert = quote {
      liftQuery(states).foreach { state =>
        query[PullerState]
          .insertValue(state)
          .onConflictUpdate(_.integration)((t, e) => t.value -> e.value)
      }
    }

    run(upsert).unit
  }

  def load(integrationType: ContentFeedIntegrationType): IO[SQLException, List[PullerState]] = {
    val select = quote { integrationType: String =>
      sql"""SELECT * FROM puller_state
        WHERE value->>'type' = $integrationType """
        .as[Query[PullerState]]
    }

    run(select(lift(integrationType.entryName)))
  }
}
