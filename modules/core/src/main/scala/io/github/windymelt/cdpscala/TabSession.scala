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

object TabSession {
  type TabSessionIO = Resource[IO, NewTabResult]
  private val client: IO[Client[IO]] = JdkHttpClient.simple[IO]

  private def urlForChromeProcess(c: ChromeProcess): Uri =
    Uri.unsafeFromString(s"http://${c.host}:${c.port}")

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
      urlForChromeProcess(chromeProcess) / "json" / "version"
    )
  } yield versionString

  type NewTabResult = % {
    val id: String
    val webSocketDebuggerUrl: String
  }
  def newTab(chromeProcess: ChromeProcess): TabSessionIO = Resource.eval {
    import org.http4s.circe.CirceEntityDecoder.*
    // TODO: close tab automatically
    for
      c <- client
      tab <- c.expect[NewTabResult]:
        Request(
          PUT,
          urlForChromeProcess(
            chromeProcess
          ) / "json" / "new" +? ("url", "https://example.com")
        )
    yield tab
  }

  def closeTab(chromeProcess: ChromeProcess, tabId: String): IO[Unit] =
    for
      c <- client
      _ <- c.expect[String]:
        urlForChromeProcess(chromeProcess) / "json" / "close" / tabId
    yield ()

  def openWsSession(
      tab: NewTabResult
  ): IO[Resource[IO, WSConnectionHighLevel[IO]]] = {
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
}
