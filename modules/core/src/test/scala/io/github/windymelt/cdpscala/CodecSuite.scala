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

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.windymelt.cdpscala.protocol.*

class CodecSuite extends munit.FunSuite {
  test("optional parameters that are None are omitted, not sent as null") {
    val params = Page.NavigateParams(url = "https://example.com")
    assertEquals(
      params.asJsonObject.toMap,
      Map("url" -> Json.fromString("https://example.com"))
    )
  }

  test("enums encode to their protocol value") {
    val params = Page.CaptureScreenshotParams(
      format = Some(Page.CaptureScreenshotFormat.Png),
      quality = Some(80)
    )
    assertEquals(
      params.asJson,
      Json.obj(
        "format" -> Json.fromString("png"),
        "quality" -> Json.fromInt(80)
      )
    )
  }

  test("unknown enum values decode to the fallback case") {
    assertEquals(
      Json.fromString("hologram").as[Page.CaptureScreenshotFormat],
      Right(Page.CaptureScreenshotFormat.Unrecognized("hologram"))
    )
    assertEquals(
      Json.fromString("jpeg").as[Page.CaptureScreenshotFormat],
      Right(Page.CaptureScreenshotFormat.Jpeg)
    )
  }

  test("fields clashing with Object members keep their JSON key") {
    val props =
      BluetoothEmulation.CharacteristicProperties(`notify_` = Some(true))
    assertEquals(props.asJson, Json.obj("notify" -> Json.True))
    assertEquals(
      Json
        .obj("notify" -> Json.True)
        .as[BluetoothEmulation.CharacteristicProperties]
        .map(_.`notify_`),
      Right(Some(true))
    )
  }

  test("keyword field names round-trip (Runtime.RemoteObject.type)") {
    val json =
      parse("""{"type":"number","value":42,"description":"42"}""").toOption.get
    val obj = json.as[Runtime.RemoteObject].toOption.get
    assertEquals(obj.`type`, Runtime.RemoteObjectType.Number)
    assertEquals(obj.value, Some(Json.fromInt(42)))
  }

  test("recursive types decode (DOM.Node children)") {
    val json = parse(
      """{"nodeId":1,"backendNodeId":1,"nodeType":9,"nodeName":"#document","localName":"","nodeValue":"",
        |"children":[{"nodeId":2,"backendNodeId":2,"nodeType":1,"nodeName":"HTML","localName":"html","nodeValue":""}]}""".stripMargin
    ).toOption.get
    val node = json.as[DOM.Node]
    assertEquals(
      node.map(_.children.map(_.map(_.nodeName))),
      Right(Some(List("HTML")))
    )
  }

  test("event payloads carry their method name") {
    assertEquals(
      summon[CDPEvent[Page.Events.LoadEventFired]].method,
      "Page.loadEventFired"
    )
  }
}
