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
import org.http4s.client.websocket.WSDataFrame
import TabSession.WSSession

object EventHandling {
  type Registry[A] = String => IO[A]

  extension (session: Resource[IO, WSSession])
    def withEventHandling: Resource[IO, WSSession] =
      session.map: ws =>
        val handler: WSDataFrame => IO[Unit] =
          f =>
            IO.println(s"<-- ${f.toString().take(200)}") // stub handler.
            // TODO: implement register/abandon mechanism.

        // intercept stream and tap
        val rs = ws.receiveStream.evalTap(handler)

        new WSSession {
          def send: WSDataFrame => IO[Unit] = ws.send
          def receiveStream: fs2.Stream[IO, WSDataFrame] = rs
          def receive: IO[Option[WSDataFrame]] =
            rs.head.compile.toList.map(_.headOption)
        }
}
