package dev.vhonta.content.youtube

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import zio._

object GoogleModule {
  private val transportLayer: ULayer[HttpTransport] = ZLayer.succeed(GoogleNetHttpTransport.newTrustedTransport())
  private val jsonFactoryLayer: ULayer[JsonFactory] = ZLayer.succeed(new GsonFactory)

  val make: ULayer[HttpTransport with JsonFactory] = transportLayer ++ jsonFactoryLayer
}
