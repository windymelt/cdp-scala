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
import cats.effect.kernel.Deferred
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import cats.syntax.all._

object EventRegistry {
  enum EventType:
    case LifecycleEvent(name: String)

  case class RegistryState(
      waiters: Map[EventType, List[Deferred[IO, Unit]]] = Map.empty
  )

  trait Registry {
    def waitFor(eventType: EventType): IO[Unit]
    def fire(eventType: EventType): IO[Unit]
  }

  def create: IO[Registry] = for {
    stateRef <- Ref.of[IO, RegistryState](RegistryState())
    eventQueue <- Queue.unbounded[IO, EventType]
    _ <- eventDispatcher(eventQueue, stateRef).start
  } yield new Registry {
    def waitFor(eventType: EventType): IO[Unit] = for {
      waiter <- Deferred[IO, Unit]
      _ <- stateRef.update { state =>
        val waiters = state.waiters.getOrElse(eventType, List.empty)
        state.copy(waiters = state.waiters + (eventType -> (waiter :: waiters)))
      }
      result <- waiter.get
    } yield result

    def fire(eventType: EventType): IO[Unit] =
      IO.println(s"! firing event: $eventType") >> eventQueue.offer(eventType)
  }

  private def eventDispatcher(
      eventQueue: Queue[IO, EventType],
      stateRef: Ref[IO, RegistryState]
  ): IO[Unit] = {
    val process = for {
      eventType <- eventQueue.take
      _ <- stateRef
        .modify { state =>
          val waiters = state.waiters.getOrElse(eventType, List.empty)
          val newState = state.copy(waiters = state.waiters - eventType)
          (newState, waiters)
        }
        .flatMap { waiters =>
          waiters.traverse_(_.complete(()))
        }
    } yield ()

    process.foreverM
  }
}
