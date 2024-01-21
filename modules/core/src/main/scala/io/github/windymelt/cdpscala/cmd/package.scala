package io.github.windymelt.cdpscala

import cats.effect.IO
import com.github.tarao.record4s.%
import org.http4s.client.websocket.WSConnectionHighLevel
import org.http4s.client.websocket.WSFrame

package object cmd {
  type CDPWSCommand[R <: %] = % {
    val id: Int
    val method: String
    val params: R
  }
  def cmd[R <: %, Result <: %](
      session: WSConnectionHighLevel[IO],
      id: Int,
      method: String,
      params: R
  )(using io.circe.Encoder[R], io.circe.Decoder[Result]): IO[Result] =
    import com.github.tarao.record4s.circe.Codec.encoder
    import io.circe.generic.auto.*
    import io.circe.parser.parse
    import io.circe.syntax.*

    val cmd: CDPWSCommand[R] = %(
      id = id,
      method = method,
      params = params
    )
    for
      _ <- IO.println(cmd.asJson.spaces2)
      _ <- session.send(WSFrame.Text(cmd.asJson.noSpaces))
      resp <- session
        // a backpressured stream of incoming frames
        .receiveStream
        // we do not care about Binary frames (and will never receive any)
        .collect { case WSFrame.Text(str, _) => str }
        // .evalTap(str => IO.println(str))
        .head
        .compile
        .lastOrError
    yield parse(resp)
      .flatMap(_.as[Result])
      .toOption
      .get /* TODO: Error handling */
}
