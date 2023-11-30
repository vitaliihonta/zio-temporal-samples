package dev.vhonta.ukvi.visa.api

import zio._
import zio.http._

object HomepageApi {
  val make: ULayer[HomepageApi] =
    ZLayer.derive[HomepageApi]
}

class HomepageApi() extends AbstractApi {
  override val routes: Routes[Any, Response] =
    Routes[Any, Throwable](
      Method.GET / "assets" / trailing -> handler { (path: Path, _: Request) =>
        Handler.fromResource(s"static/assets/${path.segments.mkString("/")}")
      }.flatten,
      RoutePattern.GET ->
        Handler.fromResource("static/index.html")
    ).handleError(Response.fromThrowable)
}
