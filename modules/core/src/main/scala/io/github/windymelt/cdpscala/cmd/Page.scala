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
package cmd

import cats.effect.IO
import cats.effect.std.Random
import com.github.tarao.record4s.%
import com.github.tarao.record4s.circe.Codec.decoder
import TabSession.WSSession

object Page:
  type NavigateResult = % {
    val id: Int
    val result: % {
      val frameId: FrameId
      val loaderId: Option[Network.LoaderId]
      val errorText: Option[String]
    }
  }
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
  type TransitionType = "link" | "typed" | "address_bar" | "auto_bookmark" |
    "auto_subframe" | "manual_subframe" | "generated" | "auto_toplevel" |
    "form_submit" | "reload" | "keyword" | "keyword_generated" | "other"
  type FrameId = String
  type ReferrerPolicy = "noReferrer" | "noReferrerWhenDowngrade" | "origin" |
    "originWhenCrossOrigin" | "sameOrigin" | "strictOrigin" |
    "strictOriginWhenCrossOrigin" | "unsafeUrl"

  extension (session: WSSession)
    def navigate(
        url: String,
        referrer: Option[String] = None,
        transitionType: Option[TransitionType] = None,
        frameId: Option[FrameId] = None,
        @experimental referrerPolicy: Option[ReferrerPolicy] = None
    )(using Random[IO]): IO[NavigateResult] =
      import com.github.tarao.record4s.circe.Codec.encoder
      for
        id <- randomCommandID()
        r <- cmd(
          session,
          id,
          "Page.navigate",
          %(
            url = url,
            transitionType = transitionType: Option[String],
            frameId = frameId
          )
        ): IO[NavigateResult]
      yield r

    def captureScreenshot(
        format: "jpeg" | "png" | "webp",
        quality: Option[Int] = None, // matters only when format is jpeg
        viewport: Option[Viewport] = None,
        @experimental fromSurface: Option[Boolean] = None,
        @experimental captureBeyondViewport: Option[Boolean] = None,
        @experimental optimizeForSpeed: Option[Boolean] = None
    )(using Random[IO]): IO[String] =
      import com.github.tarao.record4s.circe.Codec.encoder
      val takeShot: Int => IO[CaptureScreenshotResult] = id =>
        cmd(
          session,
          id,
          "Page.captureScreenshot",
          %(
            format = format: String,
            quality = quality,
            clip = viewport,
            fromSurface = fromSurface,
            captureBeyondViewport = captureBeyondViewport,
            optimizeForSpeed = optimizeForSpeed
          )
        )
      for
        id <- randomCommandID()
        shot <- takeShot(id).map(_.result.data)
      yield shot

    def setLifecycleEventsEnabled(enabled: Boolean)(using
        Random[IO]
    ): IO[Unit] =
      import com.github.tarao.record4s.circe.Codec.encoder
      for
        id <- randomCommandID()
        _ <- cmd(
          session,
          id,
          "Page.setLifecycleEventsEnabled",
          %(enabled = enabled)
        )
      yield ()
