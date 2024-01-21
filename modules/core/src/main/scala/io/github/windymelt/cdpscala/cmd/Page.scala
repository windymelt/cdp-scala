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

package io.github.windymelt.cdpscala.cmd

import cats.effect.IO
import cats.effect.std.Random
import com.github.tarao.record4s.%
import com.github.tarao.record4s.circe.Codec.decoder
import org.http4s.client.websocket.WSConnectionHighLevel

object Page:
  type CaptureScreenshotResult = % {
    val id: Int
    val result: % { val data: String }
  }
  type Viewport = % {
    val x: Int
    val y: Int
    val width: Int
    val height: Int
    val scale: Double
  }
  extension (session: WSConnectionHighLevel[IO])
    def navigate(url: String)(using Random[IO]): IO[Unit] =
      import com.github.tarao.record4s.circe.Codec.encoder
      for
        id <- randomCommandID()
        r <- cmd(
          session,
          id,
          "Page.navigate",
          %(url = url)
        )
      yield ()

    def captureScreenshot(
        format: "jpeg" | "png" | "webp", /* TODO: more options available */
        viewport: Option[Viewport] = None
    )(using Random[IO]): IO[String] =
      import com.github.tarao.record4s.circe.Codec.encoder
      val takeShot: Int => IO[CaptureScreenshotResult] = id =>
        cmd(
          session,
          id,
          "Page.captureScreenshot",
          %(
            format = format: String,
            clip = viewport
          )
        )
      for
        id <- randomCommandID()
        shot <- takeShot(id).map(_.result.data)
      yield shot
