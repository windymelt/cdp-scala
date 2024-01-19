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
import cats.effect.IOApp

object Main extends IOApp.Simple {
  def run: IO[Unit] = for {
    _ <- IO.delay(println(msg))
    _ <- ChromeProcess.spawn().use { cp =>
      for {
        _ <- IO.println(cp)
        _ <- TabSession.newTab(cp).use { ts =>
          for
            _ <- IO.println("new tab opened")
            _ <- IO.println(ts)
            wsSession <- TabSession.openWsSession(ts)
            _ <- wsSession.use { s => IO.println(s) }
            _ <- IO.println("closing tab")
            _ <- TabSession.closeTab(cp, ts.id)
            _ <- IO.println("tab closed")
          yield ()
        }
      } yield ()
    }
  } yield ()
}

def msg = "Chrome DevTools Protocol wrapper for Scala test command"
