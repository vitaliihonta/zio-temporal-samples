package dev.vhonta.content.youtube

import com.google.api.client.auth.oauth2.{BearerToken, Credential}
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest
import com.google.api.client.http.{HttpResponseException, HttpTransport}
import com.google.api.client.json.JsonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.{SearchResult, Subscription}
import zio._
import zio.stream._

import java.io.IOException
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import java.{util => ju}
import scala.jdk.CollectionConverters.IterableHasAsScala

object YoutubeClient {
  private val config = Config.string("application_name").nested("youtube")

  private val youtubeLayer: ZLayer[HttpTransport with JsonFactory, Config.Error, YouTube] = {
    ZLayer.fromZIO {
      ZIO.config(config).flatMap { appName =>
        ZIO.environmentWith[HttpTransport with JsonFactory] { env =>
          val creds = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
            .setTransport(env.get[HttpTransport])
            .setJsonFactory(env.get[JsonFactory])
            .build()

          new YouTube.Builder(env.get[HttpTransport], env.get[JsonFactory], creds)
            .setApplicationName(appName)
            .build()
        }
      }
    }
  }

  val make: ZLayer[HttpTransport with JsonFactory, Config.Error, YoutubeClient] =
    youtubeLayer >>> ZLayer.derive[YoutubeClient]
}

class YoutubeClient(youtube: YouTube) {

  def listSubscriptions(accessToken: OAuth2Client.AccessToken): Stream[Throwable, Subscription] = {
    YoutubeRequests.paginated(
      youtube
        .subscriptions()
        .list(ju.List.of("id", "snippet", "contentDetails"))
        .setMine(true)
        .setAccessToken(accessToken.value)
        .setPageToken(_)
    )(
      _.getItems,
      _.getNextPageToken
    )
  }

  def channelVideos(
    accessToken: OAuth2Client.AccessToken,
    channelId:   String,
    minDate:     LocalDateTime,
    maxResults:  Long
  ): Stream[Throwable, SearchResult] = {
    YoutubeRequests.paginated(
      youtube
        .search()
        .list(ju.List.of("id", "snippet"))
        .setChannelId(channelId)
        .setOrder("date")
        .setPublishedAfter(minDate.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT))
        .setType(ju.List.of("video"))
        .setAccessToken(accessToken.value)
        .setMaxResults(maxResults)
        .setPageToken(_)
    )(
      _.getItems,
      _.getNextPageToken
    )
  }
}

private[youtube] object YoutubeRequests {
  private sealed trait PaginationState { def nextToken: Option[String] }
  private case object Initial               extends PaginationState { val nextToken = None        }
  private case class HasNext(token: String) extends PaginationState { val nextToken = Some(token) }
  private case object Finished              extends PaginationState { val nextToken = None        }

  def paginated[A, Item](
    req:           String => AbstractGoogleClientRequest[A]
  )(getItems:      A => java.lang.Iterable[Item],
    nextPageToken: A => String,
    onQuotaExceed: GoogleJsonResponseException => Task[Boolean] /*continue or not*/ = defaultOnQuotaExceeded
  ): Stream[Throwable, Item] = {
    ZStream.unfoldChunkZIO[Any, Throwable, Item, PaginationState](Initial) {
      case Finished => ZIO.none
      case other =>
        val request = req(other.nextToken.orNull)
        ZIO.logDebug(s"Executing paginated request ${request.getClass}") *>
          ZIO
            .attemptBlockingIO {
              request.execute()
            }
            .map { response =>
              Some(
                Chunk.fromJavaIterable(getItems(response)) -> {
                  Option(nextPageToken(response))
                    .fold[PaginationState](ifEmpty = Finished)(HasNext)
                }
              )
            }
            .catchSome {
              case e: GoogleJsonResponseException if isQuotaExceeded(e) =>
                ZIO.whenZIO(onQuotaExceed(e))(ZIO.succeed(Chunk.empty[Item] -> other))
            }
    }
  }

  private val defaultOnQuotaExceeded: GoogleJsonResponseException => Task[Boolean] =
    _ =>
      ZIO.logWarning("Quote exceeded, finishing pagination") *>
        ZIO.succeed(false)

  private def isQuotaExceeded(e: GoogleJsonResponseException): Boolean =
    e.getDetails.getErrors.asScala.exists(_.getReason == "quotaExceeded")
}
