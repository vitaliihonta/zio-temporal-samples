package dev.vhonta.content.puller.workflows.storage

import zio._
import com.github.mjakubowski84.parquet4s
import java.io.IOException
import java.time.{LocalDate, LocalDateTime}
import java.nio.file.Paths
import ParquetSerializers._
import com.github.mjakubowski84.parquet4s.ParquetWriter
import dev.vhonta.content.ContentType
import java.util.UUID

case class ContentFeedItemParquetRow(
  topic:       Option[UUID],
  title:       String,
  description: Option[String],
  url:         String,
  publishedAt: LocalDateTime,
  contentType: ContentType)

object ParquetWritingFacade {

  def write(
    now:               LocalDate,
    datalakeOutputDir: String,
    integrationId:     Long
  )(chunk:             Chunk[ContentFeedItemParquetRow]
  ): IO[IOException, Unit] = {
    ZIO.scoped {
      for {
        uuid <- ZIO.randomWith(_.nextUUID)
        path = Paths.get(
                 datalakeOutputDir,
                 s"pulledDate=$now",
                 s"integration=$integrationId",
                 s"pull-$uuid.parquet"
               )
        writer <- ZIO.fromAutoCloseable(
                    ZIO.attemptBlockingIO(
                      ParquetWriter.of[ContentFeedItemParquetRow].build(parquet4s.Path(path))
                    )
                  )
        _ <- ZIO.attemptBlockingIO(
               writer.write(chunk)
             )
        _ <- ZIO.logInfo(s"Written records to $path")
      } yield ()
    }
  }
}
