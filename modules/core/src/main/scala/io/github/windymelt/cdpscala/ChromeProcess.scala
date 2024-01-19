package io.github.windymelt.cdpscala

import cats.effect.IO
import cats.effect.Resource

object ChromeProcess {
  type ChromeProcessIO = Resource[IO, ChromeProcess]

  val CHROME_SHELL = "chromium"

  def spawn(): ChromeProcessIO =
    Resource.make {
      IO.delay(rawSpawnChrome())
    } { proc =>
      IO.delay(proc.destroy())
    }

  private def rawSpawnChrome(): ChromeProcess = ChromeProcess(
    os
      .proc(CHROME_SHELL, "--headless", "--remote-debugging-port=9222")
      .spawn(stdout = os.Inherit, stderr = os.Inherit),
    "localhost",
    9222
  )
}

case class ChromeProcess(proc: os.SubProcess, host: String, port: Int) {
  def destroy(): Unit = proc.destroy()
}
