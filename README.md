# cdp-scala

Chrome DevTools Protocol (CDP) client for Scala 3, built on cats-effect and fs2.

Every CDP domain, command, type and event is generated from the official
protocol definition, so the whole protocol is available with types:

```scala
import cats.effect.{IO, IOApp}
import io.github.windymelt.cdpscala.*
import io.github.windymelt.cdpscala.protocol.*

object Screenshot extends IOApp.Simple:
  def run: IO[Unit] =
    Chrome.connect(LaunchOptions(executable = "chromium")).use { browser =>
      browser.newPage().use { page =>
        given CDPSession = page
        for
          _ <- Page.enable()
          _ <- Emulation.setDeviceMetricsOverride(1920, 1080, deviceScaleFactor = 1.0, mobile = false)
          _ <- page.waitFor[Page.Events.LoadEventFired](Page.navigate("https://example.com"))
          shot <- Page.captureScreenshot(format = Some(Page.CaptureScreenshotFormat.Png))
          _ <- IO.blocking(os.write.over(os.pwd / "shot.png", java.util.Base64.getDecoder.decode(shot.data)))
        yield ()
      }
    }
```

## Concepts

| | |
|---|---|
| `Chrome.connect(options)` | Starts Chrome/Chromium with `--remote-debugging-port=0` and a temporary profile, connects, and stops it on release. `Chrome.launch` only starts it; `CDPConnection.connect(wsUrl)` connects to an already running browser. |
| `CDPConnection` | One WebSocket to the browser. Commands are matched to responses by id, so they can run concurrently. |
| `CDPSession` | Where commands go. `connection.browser` talks to the browser (`Target`, `Browser`, ...); `connection.newPage()` / `connection.attach(targetId)` give a page session (flattened `Target.attachToTarget`). |
| `protocol.<Domain>` | Generated: types, `<Command>Params` / `<Command>Result`, a method per command taking `(using CDPSession)`, and `<Domain>.Events.*`. |
| Events | `session.subscribe[E]` (a `Resource` of a `Stream`), `session.events[E]`, and `session.waitFor[E](action, until)`, which subscribes before running `action` so the event cannot be missed. |
| Errors | `CDPError` (error response, with code and message), `CDPDecodingError`, `CDPConnectionClosed`. |

Generated code follows the protocol closely:

- optional parameters are `Option[...] = None` and are omitted from the request when `None`;
- string enums are Scala enums with an `Unrecognized(raw)` case, so values added by newer browsers do not break decoding;
- names that are Scala keywords are back-quoted (`` `type` ``), and fields that would clash with `Object` members get a trailing underscore (`` `notify_` ``) while keeping their JSON key.

## Protocol version

The definitions in [`protocol/`](protocol/) come from the
[`devtools-protocol`](https://www.npmjs.com/package/devtools-protocol) npm package
(version in `protocol/VERSION`). To update:

```sh
scripts/update-protocol.sh            # latest
scripts/update-protocol.sh 0.0.1747637
```

The bindings are regenerated on the next compile by `project/CdpCodegen.scala`
(an sbt source generator); nothing generated is checked in.

## Tests

`sbt test` runs the codec tests. The end-to-end tests in `ChromeSuite` run
against a real browser when `CDP_SCALA_CHROME` points to one:

```sh
CDP_SCALA_CHROME=$(which chromium) sbt test
```
