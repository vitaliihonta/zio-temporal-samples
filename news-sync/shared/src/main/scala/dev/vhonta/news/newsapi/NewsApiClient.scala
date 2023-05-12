package dev.vhonta.news.newsapi

import zio._
import sttp.model.Uri
import sttp.client3.circe._
import sttp.client3._

import scala.util.control.NoStackTrace

case class NewsApiRequestError(code: String, message: String)
    extends Exception(s"News API request failed: code=$code, message=$message")
    with NoStackTrace

object NewsApiClient {
  private val apiClientConfig =
    Config.uri("baseUri").map(Uri(_)).withDefault(uri"https://newsapi.org").nested("news.puller")

  val make: ZLayer[SttpBackend[Task, Any], Config.Error, NewsApiClient] = {
    ZLayer.fromZIO {
      ZIO.serviceWithZIO[SttpBackend[Task, Any]] { backend =>
        ZIO.config(apiClientConfig).map { baseUri =>
          new NewsApiClient(baseUri, backend)
        }
      }
    }
  }
}

class NewsApiClient(baseUri: Uri, backend: SttpBackend[Task, Any]) {
  def everything(request: EverythingRequest, apiKey: String): Task[EverythingResponse] = {
    val requestUri = uri"$baseUri/v2/everything"
      .addParams(
        Map[String, Option[String]](
          "q"        -> Some(request.query),
          "language" -> Some(request.language),
          "from"     -> request.from.map(_.toString),
          "to"       -> request.to.map(_.toString),
          "page"     -> Some(request.page.toString),
          "sortBy"   -> Some(request.sortBy.value),
          "pageSize" -> Some(request.pageSize.toString)
        ).collect { case (k, Some(v)) => k -> v }
      )

    basicRequest
      .get(requestUri)
      .header("X-Api-Key", apiKey)
      .response(asJsonAlways[NewsApiResponse[EverythingResponse]])
      .send(backend)
      .flatMap { response =>
        ZIO
          .fromEither(response.body)
          .flatMap {
            case NewsApiResponse.Ok(value)            => ZIO.succeed(value)
            case NewsApiResponse.Error(code, message) => ZIO.fail(NewsApiRequestError(code, message))
          }
      }
  }
}
