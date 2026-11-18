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

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Topic
import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.parse
import io.github.windymelt.cdpscala.protocol.Target
import org.http4s.Uri
import org.http4s.client.websocket.WSConnectionHighLevel
import org.http4s.client.websocket.WSFrame
import org.http4s.client.websocket.WSRequest
import org.http4s.jdkhttpclient.JdkWSClient

/** A WebSocket connection to a browser's DevTools endpoint.
  *
  * Commands are matched to their responses by id, so any number of commands may
  * be in flight concurrently. Events are fanned out to every subscriber.
  */
final class CDPConnection private (
    ws: WSConnectionHighLevel[IO],
    nextId: Ref[IO, Long],
    state: Ref[IO, CDPConnection.State],
    topic: Topic[IO, RawEvent]
) {
  import CDPConnection.*

  /** Session talking to the browser itself (`Target`, `Browser`, ...). */
  val browser: CDPSession = sessionFor(None)

  /** Session of a target attached with `flatten = true`. */
  def session(sessionId: String): CDPSession = sessionFor(Some(sessionId))

  /** Attaches to an existing target and returns its session. */
  def attach(targetId: Target.TargetID): IO[CDPSession] =
    Target
      .attachToTarget(targetId, flatten = Some(true))(using browser)
      .map(r => session(r.sessionId))

  /** Opens a new page (tab), closing it when the resource is released. */
  def newPage(url: String = "about:blank"): Resource[IO, CDPSession] =
    Resource
      .make(Target.createTarget(url)(using browser).map(_.targetId)) { id =>
        Target.closeTarget(id)(using browser).void.handleError(_ => ())
      }
      .evalMap(attach)

  private def sessionFor(id: Option[String]): CDPSession = new CDPSession {
    def sessionId: Option[String] = id
    def send(method: String, params: JsonObject): IO[Json] =
      CDPConnection.this.send(method, params, id)
    def subscribeRaw: Resource[IO, Stream[IO, RawEvent]] =
      topic.subscribeAwaitUnbounded.map(_.filter(_.sessionId == id))
  }

  private def send(
      method: String,
      params: JsonObject,
      sessionId: Option[String]
  ): IO[Json] =
    for {
      id <- nextId.getAndUpdate(_ + 1)
      reply <- Deferred[IO, Either[Throwable, Json]]
      _ <- state.modify { s =>
        s.closed match {
          case Some(e) => (s, IO.raiseError(e))
          case None    => (s.copy(pending = s.pending + (id -> reply)), IO.unit)
        }
      }.flatten
      message = JsonObject.fromIterable(
        List(
          "id" -> Json.fromLong(id),
          "method" -> Json.fromString(method),
          "params" -> Json.fromJsonObject(params)
        ) ++ sessionId.map(s => "sessionId" -> Json.fromString(s))
      )
      result <- (ws.sendText(Json.fromJsonObject(message).noSpaces) >>
        reply.get.rethrow.adaptError { case e: CDPError =>
          e.copy(method = method)
        }).guarantee(state.update(s => s.copy(pending = s.pending - id)))
    } yield result

  private def dispatch(text: String): IO[Unit] =
    parse(text).toOption.flatMap(_.asObject) match {
      case None => IO.unit // not a protocol message; ignore
      case Some(msg) =>
        msg("id").flatMap(_.asNumber).flatMap(_.toLong) match {
          case Some(id) =>
            val outcome: Either[Throwable, Json] =
              msg("error").flatMap(_.asObject) match {
                case Some(err) =>
                  Left(
                    CDPError(
                      method = "",
                      code = err("code")
                        .flatMap(_.asNumber)
                        .flatMap(_.toInt)
                        .getOrElse(0),
                      message =
                        err("message").flatMap(_.asString).getOrElse(""),
                      data = err("data").flatMap(_.asString)
                    )
                  )
                case None => Right(msg("result").getOrElse(Json.obj()))
              }
            state.get.flatMap(_.pending.get(id).traverse_(_.complete(outcome)))
          case None =>
            msg("method").flatMap(_.asString).traverse_ { method =>
              topic
                .publish1(
                  RawEvent(
                    method,
                    msg("params").getOrElse(Json.obj()),
                    msg("sessionId").flatMap(_.asString)
                  )
                )
                .void
            }
        }
    }

  private def close(reason: Throwable): IO[Unit] =
    state
      .modify(s => (State(Some(reason), Map.empty), s.pending.values.toList))
      .flatMap(_.traverse_(_.complete(Left(reason)))) >> topic.close.void

  private def readLoop: IO[Unit] =
    ws.receiveStream
      .collect { case WSFrame.Text(text, _) => text }
      .evalMap(dispatch)
      .compile
      .drain
      .attempt
      .flatMap {
        case Right(_) => close(CDPConnectionClosed("closed by the browser"))
        case Left(e)  => close(CDPConnectionClosed(e.getMessage))
      }
}

object CDPConnection {
  private final case class State(
      closed: Option[Throwable],
      pending: Map[Long, Deferred[IO, Either[Throwable, Json]]]
  )

  /** Connects to a DevTools WebSocket endpoint, e.g. the browser endpoint
    * printed by Chrome as `DevTools listening on ws://...`.
    */
  def connect(webSocketDebuggerUrl: String): Resource[IO, CDPConnection] =
    for {
      uri <- Resource.eval(IO.fromEither(Uri.fromString(webSocketDebuggerUrl)))
      httpClient <- Resource.eval(IO(java.net.http.HttpClient.newHttpClient()))
      ws <- JdkWSClient[IO](httpClient).connectHighLevel(WSRequest(uri))
      nextId <- Resource.eval(Ref.of[IO, Long](1L))
      state <- Resource.eval(Ref.of[IO, State](State(None, Map.empty)))
      topic <- Resource.eval(Topic[IO, RawEvent])
      conn = new CDPConnection(ws, nextId, state, topic)
      _ <- conn.readLoop.background
      _ <- Resource.onFinalize(conn.close(CDPConnectionClosed("released")))
    } yield conn
}
