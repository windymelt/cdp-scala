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
import cats.effect.std.Random
import com.github.tarao.record4s.%
import org.http4s.client.websocket.WSFrame
import TabSession.WSSession

package object cmd {
  type CDPWSCommand[R <: %] = % {
    val id: Int
    val method: String
    val params: R
  }
  def cmd[R <: %, Result <: %](
      session: WSSession,
      id: Int,
      method: String,
      params: R
  )(using enc: io.circe.Encoder[R], dec: io.circe.Decoder[Result]): IO[Result] =
    import com.github.tarao.record4s.circe.Codec.encoder
    import io.circe.generic.auto.*
    import io.circe.parser.parse
    import io.circe.syntax.*
    import io.circe.Encoder
    // `None` field is converted into `null`, then eliminated by circe
    given Encoder[R] = enc.mapJson(_.dropNullValues)

    val cmd: CDPWSCommand[R] = %(
      id = id,
      method = method,
      params = params
    )
    for
      _ <- session.send(WSFrame.Text(cmd.asJson.noSpaces))
      _ <- IO.println(
        s"--> ${WSFrame.Text(cmd.asJson.noSpaces).toString.take(200)}"
      )
      resp <- session.receiveStream
        .collect { case WSFrame.Text(str, _) => str }
        .map(parse)
        .collect { case Right(j) => j }
        .find(
          _.hcursor
            .downField("id")
            .focus
            .flatMap(_.asNumber)
            .flatMap(_.toInt)
            .map(_ == id)
            .getOrElse(false)
        )
        .compile
        .toList
        .map(_.head)
    yield resp.as[Result].toOption.get /* TODO: Error handling */

  private[cmd] def randomCommandID()(using r: Random[IO]): IO[Int] = r.nextInt

  /** Annotates that this command/parameter is experimental on CDP.
    */
  private[cmd] class experimental extends scala.annotation.StaticAnnotation
}
