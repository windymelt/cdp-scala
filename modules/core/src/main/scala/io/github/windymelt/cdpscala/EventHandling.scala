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

import cats.syntax.traverse.*
import cats.effect.IO
import cats.effect.Resource
import org.http4s.client.websocket.WSDataFrame
import TabSession.WSSession
import io.circe.parser.parse
import io.github.windymelt.cdpscala.EventRegistry.EventType

object EventHandling {
  type Registry[A] = String => IO[A]

  trait EventHandlingWSSession extends WSSession {
    def waitForLifecycleEvent(name: String): IO[Unit]
  }

  extension (session: Resource[IO, WSSession])
    def withEventHandling: Resource[IO, EventHandlingWSSession] =
      for {
        eventRegistry <- Resource.eval(EventRegistry.create)
        ws <- session
        clientQueue <- Resource.eval(
          cats.effect.std.Queue.unbounded[IO, WSDataFrame]
        )
        eventHandlingSession <- createEventHandlingSession(
          eventRegistry,
          ws,
          clientQueue
        )
      } yield eventHandlingSession

  // TODO: track loading id
  private def parseJsonEvent(f: WSDataFrame): Option[EventType] = {
    val frameText = f.toString()
    val jsonStr = frameText.substring(
      frameText.indexOf('{'),
      frameText.lastIndexOf('}') + 1
    )
    parse(jsonStr).toOption.flatMap { json =>
      val cursor = json.hcursor
      cursor
        .get[String]("method")
        .toOption
        .collect { case "Page.lifecycleEvent" =>
          cursor
            .downField("params")
            .as[io.circe.Json]
            .toOption
            .flatMap(_.hcursor.get[String]("name").toOption)
            .map(EventType.LifecycleEvent.apply)
        }
        .flatten
    }
  }

  private def createEventHandlingSession(
      eventRegistry: EventRegistry.Registry,
      ws: WSSession,
      clientQueue: cats.effect.std.Queue[IO, WSDataFrame]
  ): Resource[IO, EventHandlingWSSession] = {
    val eventHandler: WSDataFrame => IO[Unit] =
      f =>
        for {
          _ <- IO.println(s"<-- ${f.toString().take(200)}")
          lifecycleEvent <- IO.pure(parseJsonEvent(f))
          _ <- lifecycleEvent.traverse(eventRegistry.fire)
        } yield ()

    ws.receiveStream
      .evalMap(frame => clientQueue.offer(frame) >> eventHandler(frame))
      .compile
      .drain
      .background
      .map { _ =>
        val frameStream = fs2.Stream.fromQueueUnterminated(clientQueue)

        new EventHandlingWSSession {
          def send: WSDataFrame => IO[Unit] = ws.send
          def receiveStream: fs2.Stream[IO, WSDataFrame] = frameStream
          def receive: IO[Option[WSDataFrame]] =
            frameStream.head.compile.toList.map(_.headOption)

          def waitForLifecycleEvent(name: String): IO[Unit] =
            eventRegistry.waitFor(
              EventRegistry.EventType.LifecycleEvent(name)
            )
        }
      }
  }
}
