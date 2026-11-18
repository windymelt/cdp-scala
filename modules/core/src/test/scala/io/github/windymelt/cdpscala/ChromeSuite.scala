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
import cats.syntax.all.*
import io.circe.Json
import io.github.windymelt.cdpscala.protocol.*

import java.io.ByteArrayInputStream
import java.util.Base64
import scala.concurrent.duration.*

/** End-to-end tests against a real browser. Set `CDP_SCALA_CHROME` to a Chrome
  * / Chromium binary to run them (they are skipped otherwise).
  */
class ChromeSuite extends munit.CatsEffectSuite {
  override def munitIOTimeout: Duration = 90.seconds

  private val chrome = sys.env.get("CDP_SCALA_CHROME")

  private val browser = ResourceSuiteLocalFixture(
    "browser",
    Resource.eval(IO(chrome)).flatMap {
      case Some(exe) =>
        Chrome
          .connect(LaunchOptions(executable = exe, noSandbox = true))
          .map(Some(_))
      case None => Resource.pure[IO, Option[CDPConnection]](None)
    }
  )

  override def munitFixtures = List(browser)

  /** The shared browser, or a skipped test when no browser is configured. */
  private def connection: IO[CDPConnection] =
    IO(browser()).flatMap {
      case Some(conn) => IO.pure(conn)
      case None =>
        IO(assume(false, "CDP_SCALA_CHROME is not set")) >>
          IO.raiseError(new IllegalStateException("unreachable"))
    }

  private def withPage[A](f: CDPSession ?=> IO[A]): IO[A] =
    connection.flatMap(_.newPage().use(page => f(using page)))

  private val html =
    """data:text/html;charset=utf-8,<html><body style="margin:0">
      |<div style="position:fixed;left:0;bottom:0;width:100%;height:25%;background:rgba(0,0,0,0.5)"></div>
      |</body></html>""".stripMargin.replace("\n", "")

  test("browser version is reported") {
    connection.flatMap { conn =>
      Browser
        .getVersion()(using conn.browser)
        .map(v => assert(v.product.nonEmpty))
    }
  }

  test("transparent 1920x1080 screenshot of a page (zmm use case)") {
    withPage {
      for {
        _ <- Page.enable()
        _ <- Emulation.setDeviceMetricsOverride(
          1920,
          1080,
          deviceScaleFactor = 1.0,
          mobile = false
        )
        _ <- Emulation.setDefaultBackgroundColorOverride(
          Some(DOM.RGBA(0, 0, 0, Some(0.0)))
        )
        _ <- summon[CDPSession].waitFor[Page.Events.LoadEventFired](
          Page.navigate(html)
        )
        shot <- Page.captureScreenshot(format =
          Some(Page.CaptureScreenshotFormat.Png)
        )
        image <- IO.blocking(
          javax.imageio.ImageIO.read(
            ByteArrayInputStream(Base64.getDecoder.decode(shot.data))
          )
        )
      } yield {
        assertEquals((image.getWidth, image.getHeight), (1920, 1080))
        assertEquals(image.getRGB(5, 5) >>> 24, 0, "top is transparent")
        assertEquals(
          image.getRGB(5, 1075) >>> 24,
          128,
          "band is half transparent"
        )
      }
    }
  }

  test("JavaScript evaluation and concurrent commands") {
    withPage {
      (1 to 20).toList
        .parTraverse(i =>
          Runtime.evaluate(s"$i * 2", returnByValue = Some(true))
        )
        .map(rs =>
          assertEquals(
            rs.map(_.result.value),
            (1 to 20).toList.map(i => Some(Json.fromInt(i * 2)))
          )
        )
    }
  }

  test("protocol errors surface as CDPError") {
    withPage {
      Page.navigate("not a url").attempt.map {
        case Left(e: CDPError) => assertEquals(e.method, "Page.navigate")
        case other             => fail(s"expected CDPError, got $other")
      }
    }
  }

  test("events are delivered per session") {
    withPage {
      for {
        _ <- Runtime.enable()
        msg <- summon[CDPSession]
          .waitFor[Runtime.Events.ConsoleAPICalled](
            Runtime.evaluate("console.log('hello from cdp-scala')"),
            until = _.`type` == Runtime.ConsoleAPICalledType.Log
          )
          .timeout(10.seconds)
      } yield assertEquals(
        msg.args.headOption.flatMap(_.value),
        Some(Json.fromString("hello from cdp-scala"))
      )
    }
  }
}
