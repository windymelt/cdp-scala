package io.github.windymelt.cdpscala

import cats.effect.IO
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.jdkhttpclient.JdkHttpClient

object TabSession {
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
}
