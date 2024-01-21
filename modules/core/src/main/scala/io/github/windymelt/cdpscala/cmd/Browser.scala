package io.github.windymelt.cdpscala.cmd

import cats.effect.IO
import cats.effect.std.Random
import com.github.tarao.record4s.%
import com.github.tarao.record4s.circe.Codec.decoder
import org.http4s.client.websocket.WSConnectionHighLevel

object Browser:
  type WindowState = "normal" | "minimized" | "maximized" | "fullscreen"
  type Bounds = % {
    val left: Option[Int]
    val top: Option[Int]
    val width: Option[Int]
    val height: Option[Int]
    val windowState: Option[WindowState]
  }
  type WindowID = Int

  extension (session: WSConnectionHighLevel[IO])
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
