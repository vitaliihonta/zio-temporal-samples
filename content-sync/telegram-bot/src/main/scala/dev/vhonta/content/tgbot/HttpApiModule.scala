package dev.vhonta.content.tgbot

import dev.vhonta.content.tgbot.api.YoutubeCallbackHandlingApi
import zio._
import zio.http._

object HttpApiModule {
  // never returns
  val serveApi: ZIO[YoutubeCallbackHandlingApi, Throwable, Nothing] = {
    ZIO.serviceWithZIO[YoutubeCallbackHandlingApi] { callbackHandling =>
      ZIO.logInfo("Serving HTTP API...") *>
        Server
          .serve(callbackHandling.httpApp)
          .provide(Server.defaultWithPort(9092))
    }
  }
}
