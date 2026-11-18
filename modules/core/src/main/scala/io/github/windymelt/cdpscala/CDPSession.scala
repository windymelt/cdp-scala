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
import fs2.Stream
import io.circe.Decoder
import io.circe.Json
import io.circe.JsonObject

/** An event as it arrives on the wire. */
final case class RawEvent(
    method: String,
    params: Json,
    sessionId: Option[String]
)

/** Type class tying an event payload type to its CDP method name.
  *
  * Generated event classes (e.g. `Page.Events.LoadEventFired`) carry a given
  * instance, so they can be used with [[CDPSession.subscribe]] and
  * [[CDPSession.waitFor]] directly.
  */
final case class CDPEvent[E](method: String)(using val decoder: Decoder[E])

/** Error response returned by the browser for a command. */
final case class CDPError(
    method: String,
    code: Int,
    message: String,
    data: Option[String]
) extends RuntimeException(
      s"$method failed: $message (code $code)${data.fold("")(d => s": $d")}"
    )

/** The browser answered with a payload this library could not decode. */
final case class CDPDecodingError(method: String, cause: io.circe.Error)
    extends RuntimeException(s"cannot decode response of $method", cause)

/** The connection to the browser is gone. */
final case class CDPConnectionClosed(reason: String)
    extends RuntimeException(s"CDP connection closed: $reason")

/** A channel commands can be sent through: the browser itself, or a target
  * (page, worker, ...) attached with `flatten = true`.
  *
  * Generated command methods take the session as a context parameter:
  * {{{
  * given CDPSession = page
  * Page.navigate("https://example.com")
  * }}}
  */
trait CDPSession {

  /** `None` for the browser session, the attached target's session otherwise.
    */
  def sessionId: Option[String]

  /** Sends a raw command and returns its raw `result`. Fails with [[CDPError]]
    * when the browser returns an error.
    */
  def send(method: String, params: JsonObject): IO[Json]

  /** Subscribes to the events of this session. Events published after the
    * resource is acquired are buffered until the stream is consumed, so
    * subscribe first and then trigger the action that causes the event.
    */
  def subscribeRaw: Resource[IO, Stream[IO, RawEvent]]

  final def call[R](method: String, params: JsonObject)(using
      decoder: Decoder[R]
  ): IO[R] =
    send(method, params).flatMap { json =>
      IO.fromEither(
        decoder.decodeJson(json).left.map(CDPDecodingError(method, _))
      )
    }

  final def callUnit(method: String, params: JsonObject): IO[Unit] =
    send(method, params).void

  /** Typed variant of [[subscribeRaw]]. */
  final def subscribe[E](using ev: CDPEvent[E]): Resource[IO, Stream[IO, E]] =
    subscribeRaw.map(
      _.filter(_.method == ev.method).evalMap { raw =>
        IO.fromEither(
          ev.decoder
            .decodeJson(raw.params)
            .left
            .map(CDPDecodingError(ev.method, _))
        )
      }
    )

  /** All events of type `E` from now on. */
  final def events[E](using CDPEvent[E]): Stream[IO, E] =
    Stream.resource(subscribe[E]).flatten

  /** Runs `action` and waits for the first event of type `E` satisfying `until`
    * emitted after the subscription was set up (so an event caused by `action`
    * cannot be missed).
    *
    * {{{
    * page.waitFor[Page.Events.LoadEventFired](Page.navigate(url))
    * }}}
    */
  final def waitFor[E](action: IO[Any], until: E => Boolean = (_: E) => true)(
      using CDPEvent[E]
  ): IO[E] =
    subscribe[E].use { events =>
      action >> events.find(until).compile.lastOrError
    }
}
