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

object Browser:
  type WindowState = "normal" | "minimized" | "maximized" | "fullscreen"
  type Bounds = % {
    val left: Option[Int]
    val top: Option[Int]
    val width: Option[Int]
    val height: Option[Int]
    val windowState: Option[WindowState]
  }
  opaque type WindowID = Int

  extension (session: WSSession)
    @experimental
    def getWindowForTarget(
        // targetId: Option[Target.TargetID]
    )(using
        Random[IO]
    ): IO[% { val windowId: WindowID; /* val bounds: Bounds */ }] =
      import com.github.tarao.record4s.circe.Codec.decoder
      import com.github.tarao.record4s.circe.Codec.encoder
      for
        id <- randomCommandID()
        res <- cmd[
          %, // % { /*val targetId: Option[Target.TargetID]*/ },
          % {
            val id: Int;
            val result: % { val windowId: WindowID; /*val bounds: Bounds*/ }
          }
        ](
          session,
          id,
          "Browser.getWindowForTarget",
          %(
            // targetId = targetId
          )
        )
      yield %(windowId = res.result.windowId)

    @experimental
    def setWindowBounds(windowID: WindowID, bounds: Bounds)(using
        Random[IO]
    ): IO[Unit] =
      import com.github.tarao.record4s.circe.Codec.encoder
      for
        id <- randomCommandID()
        _ <- cmd(
          session,
          id,
          "Browser.setWindowBounds",
          %(
            windowId = windowID,
            bounds = // TODO: drop null field
              bounds ++ %(windowState = bounds.windowState.getOrElse("normal"))
          )
        )
      yield ()
