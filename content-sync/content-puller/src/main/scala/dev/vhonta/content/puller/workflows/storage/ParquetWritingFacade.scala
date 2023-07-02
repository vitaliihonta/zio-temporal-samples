package dev.vhonta.content.puller.workflows.storage

import com.github.mjakubowski84.parquet4s.ParquetWriter
import com.github.mjakubowski84.parquet4s
import zio._
import java.io.IOException
import java.time.Instant
import java.nio.file.Paths
import ParquetSerializers._
import dev.vhonta.content.ContentFeedIntegrationType

object ParquetWritingFacade {
  def write[T](
    parquetWriterBuilder: ParquetWriter.Builder[T],
    now:                  Instant,
    datalakeOutputDir:    String,
    integrationType:      ContentFeedIntegrationType
  )(chunk:                Chunk[T]
  ): IO[IOException, Unit] = {
    ZIO.scoped {
      for {
        uuid <- ZIO.randomWith(_.nextUUID)
        path = Paths.get(
                 datalakeOutputDir,
                 s"pulledAt=${now.toEpochMilli}",
                 s"integrationType=${integrationType.entryName}",
                 s"pull-$uuid.parquet"
               )
        writer <- ZIO.fromAutoCloseable(ZIO.attemptBlockingIO(parquetWriterBuilder.build(parquet4s.Path(path))))
        _ <- ZIO.attemptBlockingIO(
               writer.write(chunk)
             )
        _ <- ZIO.logInfo(s"Written records to $path")
      } yield ()
    }
  }
}
