package io.github.windymelt.cdpscala

import cats.effect.IO
import cats.effect.Resource
import com.github.tarao.record4s.%
import com.github.tarao.record4s.circe.Codec.decoder
import org.http4s.Method.PUT
import org.http4s.Request
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.client.websocket.WSRequest
import org.http4s.client.websocket.WSConnectionHighLevel
import org.http4s.client.websocket.WSFrame

object TabSession {
  type TabSessionIO = Resource[IO, NewTabResult]
  private val client: IO[Client[IO]] = JdkHttpClient.simple[IO]

  type NewTabResult = % {
    val id: String
    val webSocketDebuggerUrl: String
  }
  extension (cp: ChromeProcess) {

    /** Browser version metadata.
      *
      * @see
      *   https://chromedevtools.github.io/devtools-protocol/#get-jsonversion
      * @param chromeProcess
      * @return
      */
    def browserVersion(chromeProcess: ChromeProcess): IO[String] = for {
      c <- client
      versionString <- c.expect[String](
        cp.httpUrl / "json" / "version"
      )
    } yield versionString

    def newTab(): TabSessionIO = Resource.eval {
      import org.http4s.circe.CirceEntityDecoder.*
      // TODO: close tab automatically
      for
        c <- client
        tab <- c.expect[NewTabResult]:
          Request(
            PUT,
            cp.httpUrl / "json" / "new" +? ("url", "https://example.com")
          )
      yield tab
    }

    def closeTab(tabId: String): IO[Unit] =
      for
        c <- client
        _ <- c.expect[String]:
          cp.httpUrl / "json" / "close" / tabId
      yield ()
  }

  type CDPTabSession = Resource[IO, WSConnectionHighLevel[IO]]
  def openWsSession(
      tab: NewTabResult
  ): IO[CDPTabSession] = {
    import java.net.http.HttpClient
    import org.http4s.client.websocket.WSClient
    import org.http4s.jdkhttpclient.JdkWSClient

    val wsClient: IO[WSClient[IO]] = IO(HttpClient.newHttpClient())
      .map { httpClient => JdkWSClient[IO](httpClient) }

    for ws <- wsClient
    yield ws.connectHighLevel(
      WSRequest(Uri.unsafeFromString(tab.webSocketDebuggerUrl))
    )
  }

  def navigate(session: WSConnectionHighLevel[IO], url: String): IO[Unit] =
    for
      _ <- session.send(
        WSFrame.Text(
          """{"id":1,"method":"Page.navigate","params":{"url":"https://example.com/"}}"""
        )
      )
      _ <- session
        // a backpressured stream of incoming frames
        .receiveStream
        // we do not care about Binary frames (and will never receive any)
        .collect { case WSFrame.Text(str, _) => str }
        // send back the modified text
        .evalTap(str => IO.println(str))
        .head
        .compile
        .drain
    yield ()
}
