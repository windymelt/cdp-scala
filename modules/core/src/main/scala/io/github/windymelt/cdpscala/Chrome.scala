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
import scala.concurrent.Promise
import scala.concurrent.duration.*

/** How to start the browser.
  *
  * @param executable
  *   Chrome / Chromium binary (name on `PATH` or absolute path)
  * @param headless
  *   run without a window
  * @param noSandbox
  *   pass `--no-sandbox` (needed e.g. when running as root in containers)
  * @param args
  *   extra command line switches
  * @param userDataDir
  *   profile directory; a temporary one is created and removed when omitted
  * @param startupTimeout
  *   how long to wait for the DevTools endpoint
  */
final case class LaunchOptions(
    executable: String = "chromium",
    headless: Boolean = true,
    noSandbox: Boolean = false,
    args: Seq[String] = Nil,
    userDataDir: Option[os.Path] = None,
    startupTimeout: FiniteDuration = 30.seconds
)

/** A running browser process and its browser-level DevTools endpoint. */
final case class ChromeProcess(
    process: os.SubProcess,
    webSocketDebuggerUrl: String
)

object Chrome {
  private val Listening = """DevTools listening on (ws://\S+)""".r.unanchored

  /** Launches a browser and connects to it.
    *
    * {{{
    * Chrome.connect(LaunchOptions(executable = "chromium")).use { browser =>
    *   browser.newPage().use { page =>
    *     given CDPSession = page
    *     Page.navigate("https://example.com").void
    *   }
    * }
    * }}}
    */
  def connect(
      options: LaunchOptions = LaunchOptions()
  ): Resource[IO, CDPConnection] =
    launch(options).flatMap(p => CDPConnection.connect(p.webSocketDebuggerUrl))

  /** Starts the browser with `--remote-debugging-port=0` (a free port is chosen
    * by the browser) and waits until its DevTools endpoint is printed. The
    * process is terminated when the resource is released.
    */
  def launch(
      options: LaunchOptions = LaunchOptions()
  ): Resource[IO, ChromeProcess] =
    for {
      dataDir <- options.userDataDir match {
        case Some(dir) => Resource.pure[IO, os.Path](dir)
        case None =>
          Resource.make(IO.blocking(os.temp.dir(prefix = "cdp-scala-")))(dir =>
            IO.blocking(os.remove.all(dir)).handleError(_ => ())
          )
      }
      endpoint <- Resource.eval(IO(Promise[String]()))
      recentOutput = new java.util.concurrent.ConcurrentLinkedDeque[String]()
      process <- Resource.make(IO.blocking {
        os.proc(command(options, dataDir))
          .spawn(
            stdout = os.ProcessOutput.Readlines(_ => ()),
            stderr = os.ProcessOutput.Readlines { line =>
              recentOutput.addLast(line)
              if (recentOutput.size > 20) { val _ = recentOutput.pollFirst() }
              line match {
                case Listening(url) => { val _ = endpoint.trySuccess(url) }
                case _              => ()
              }
            }
          )
      })(p =>
        IO.blocking {
          p.destroy(); p.waitFor(5000); if (p.isAlive()) p.destroyForcibly()
        }
      )
      url <- Resource.eval(
        IO.fromFuture(IO(endpoint.future))
          .timeoutTo(
            options.startupTimeout,
            IO.defer {
              val output = recentOutput.toArray.mkString("\n")
              IO.raiseError(
                new RuntimeException(
                  s"browser did not expose a DevTools endpoint within ${options.startupTimeout} " +
                    s"(alive: ${process.isAlive()}). Last output:\n$output"
                )
              )
            }
          )
      )
    } yield ChromeProcess(process, url)

  private[cdpscala] def command(
      options: LaunchOptions,
      dataDir: os.Path
  ): Seq[String] =
    Seq(options.executable) ++
      Option.when(options.headless)("--headless") ++
      Option.when(options.noSandbox)("--no-sandbox") ++
      Seq(
        "--remote-debugging-port=0",
        s"--user-data-dir=$dataDir",
        "--no-first-run",
        "--no-default-browser-check"
      ) ++ options.args :+ "about:blank"
}
