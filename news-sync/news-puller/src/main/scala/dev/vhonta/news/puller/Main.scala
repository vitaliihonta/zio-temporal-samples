package dev.vhonta.news.puller

import dev.vhonta.news.puller.client.{EverythingRequest, NewsApiClient, SortBy}
import zio._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import java.time.LocalDateTime

object Main extends ZIOAppDefault {
  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    val program = ZIO.serviceWithZIO[NewsApiClient] { client =>
      for {
        response <- client.everything(
                      EverythingRequest(
                        query = "Dynamo Kyiv",
                        language = "en",
                        from = Some(LocalDateTime.now().minusDays(7)),
                        to = None,
                        sortBy = SortBy.PublishedAt,
                        pageSize = 10,
                        page = 1
                      )
                    )
        _ <- ZIO.logInfo(s"Search response: $response")
      } yield ()
    }

    program.provideSome[Scope](
      HttpClientZioBackend.layer(),
      NewsApiClient.make
    )
  }
}
