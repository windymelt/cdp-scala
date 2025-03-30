/*
 * Copyright (c) 2024 cdp-scala authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

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
import org.http4s.client.websocket.WSDataFrame

object TabSession:
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
    def browserVersion(): IO[String] = for
      c <- client
      versionString <- c.expect[String](
        cp.httpUrl / "json" / "version"
      )
    yield versionString

    def newTabAutoClose(): TabSessionIO =
      newTab().flatMap(tab => Resource.make(IO(tab))(tab => closeTab(tab.id)))

    def newTab(): TabSessionIO = Resource.eval:
      import org.http4s.circe.CirceEntityDecoder.*
      for
        c <- client
        tab <- c.expect[NewTabResult]:
          Request(
            PUT,
            cp.httpUrl / "json" / "new" +? ("url", "https://example.com")
          )
      yield tab

    def closeTab(tabId: String): IO[Unit] = for
      c <- client
      _ <- c.expect[String]:
        cp.httpUrl / "json" / "close" / tabId
    yield ()
  }

  def extractBrowserId(url: String): String = url.split("/").last

  trait WSSession:
    def send: WSDataFrame => IO[Unit]
    def receiveStream: fs2.Stream[IO, WSDataFrame]
    def receive: IO[Option[WSDataFrame]]

  class WSConnectionSession(conn: WSConnectionHighLevel[IO]) extends WSSession:
    def send: WSDataFrame => IO[Unit] = conn.send
    def receiveStream: fs2.Stream[IO, WSDataFrame] = conn.receiveStream
    def receive: IO[Option[WSDataFrame]] = conn.receive

  type CDPTabSession = Resource[IO, WSSession]
  def openWsSession(
      tab: NewTabResult
  ): IO[CDPTabSession] =
    import java.net.http.HttpClient
    import org.http4s.client.websocket.WSClient
    import org.http4s.jdkhttpclient.JdkWSClient

    val wsClient: IO[WSClient[IO]] = IO(HttpClient.newHttpClient())
      .map { httpClient => JdkWSClient[IO](httpClient) }

    for ws <- wsClient
    yield ws
      .connectHighLevel(
        WSRequest(Uri.unsafeFromString(tab.webSocketDebuggerUrl))
      )
      .map(conn => WSConnectionSession(conn))
