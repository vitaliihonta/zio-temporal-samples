package dev.vhonta.content.repository

import org.flywaydb.core.Flyway
import zio._
import javax.sql.DataSource

object DatabaseMigrator {
  // trigger driver load
  private val _ = org.postgresql.Driver.isRegistered

  val applyMigration: ZLayer[DataSource, Throwable, Unit] =
    ZLayer.scoped {
      ZIO.serviceWithZIO[DataSource] { dataSource =>
        for {
          _ <- ZIO.logInfo("Applying database migration...")
          migrationResult <- ZIO.blocking {
                               ZIO.attempt {
                                 Flyway
                                   .configure()
                                   .dataSource(dataSource)
                                   .failOnMissingLocations(true)
                                   .load()
                                   .migrate()
                               }
                             }
          _ <- ZIO.logInfo(s"Database migration succeeded result=$migrationResult")
        } yield ()
      }
    }
}
