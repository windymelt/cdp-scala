package io.github.windymelt.cdpscala.cmd

import cats.effect.IO
import com.github.tarao.record4s.%
import com.github.tarao.record4s.circe.Codec.decoder
import org.http4s.client.websocket.WSConnectionHighLevel

object Page:
  type CaptureScreenshotResult = % {
    val id: Int
    val result: % { val data: String }
  }
  def navigate(session: WSConnectionHighLevel[IO], url: String): IO[Unit] =
    import com.github.tarao.record4s.circe.Codec.encoder
    cmd(
      session,
      1 /* TODO: give random posint */,
      "Page.navigate",
      %(url = url)
    )

  def captureScreenshot(
      session: WSConnectionHighLevel[IO],
      format: "jpeg" | "png" | "webp" /* TODO: more options available */
  ): IO[String] =
    import com.github.tarao.record4s.circe.Codec.encoder
    val result: IO[CaptureScreenshotResult] =
      cmd(
        session,
        2 /* TODO: give random posint */,
        "Page.captureScreenshot",
        %(format = format: String)
      )
    result.map(_.result.data)
