package dev.vhonta.ukvi.visa.api

import zio.http._

trait AbstractApi {
  def routes: Routes[Any, Response]

  def hxRedirect(to: URL): Response =
    Response(
      status = Status.Ok,
      headers = Headers(
        Header.Custom("HX-Redirect", to.encode)
      )
    )
}
