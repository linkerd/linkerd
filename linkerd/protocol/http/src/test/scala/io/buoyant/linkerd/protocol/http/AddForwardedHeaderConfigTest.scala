package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.twitter.finagle.Stack
import io.buoyant.config.Parser
import io.buoyant.router.RouterLabel
import io.buoyant.router.http.AddForwardedHeader
import io.buoyant.test.FunSuite

class AddForwardedHeaderConfigTest extends FunSuite {

  test("parse empty") {
    val yaml = "{}"
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = (Stack.Params.empty + RouterLabel.Param("rtr")) ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By] == AddForwardedHeader.Labeler.By.default)
    assert(params[AddForwardedHeader.Labeler.For] == AddForwardedHeader.Labeler.For.default)
  }

  test("parse by=requestRandom for=requestRandom") {
    val yaml =
      s"""|by: {kind: requestRandom}
          |for: {kind: requestRandom}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = Stack.Params.empty ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedRandom.PerRequest())
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedRandom.PerRequest())
  }

  test("parse by=connectionRandom for=connectionRandom") {
    val yaml =
      s"""|by: {kind: connectionRandom}
          |for: {kind: connectionRandom}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = Stack.Params.empty ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedRandom.PerConnection())
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedRandom.PerConnection())
  }

  test("parse by=ip for=ip") {
    val yaml =
      s"""|by: {kind: ip}
          |for: {kind: ip}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = Stack.Params.empty ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ClearIp)
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ClearIp)
  }

  test("parse by=ip:port for=ip:port") {
    val yaml =
      s"""|by: {kind: "ip:port"}
          |for: {kind: "ip:port"}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = Stack.Params.empty ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ClearIpPort)
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ClearIpPort)
  }

  test("parse by=static for=static") {
    val yaml =
      s"""|by:
          |  kind: static
          |  label: router
          |for:
          |  kind: static
          |  label: client
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = Stack.Params.empty ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedStatic("router"))
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedStatic("client"))
  }

  test("parse by=router") {
    val yaml =
      s"""|by: {kind: router}
          |for: {kind: router}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = (Stack.Params.empty + RouterLabel.Param("bigfoot")) ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedStatic("bigfoot"))
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedStatic("bigfoot"))
  }

  test("parse by=ip:port for=router") {
    val yaml =
      s"""|by: {kind: "ip:port"}
          |for: {kind: router}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[AddForwardedHeaderConfig](yaml)
    val params = (Stack.Params.empty + RouterLabel.Param("bigfoot")) ++: config
    assert(params[AddForwardedHeader.Enabled] == AddForwardedHeader.Enabled(true))
    assert(params[AddForwardedHeader.Labeler.By].labeler ==
      AddForwardedHeader.Labeler.ClearIpPort)
    assert(params[AddForwardedHeader.Labeler.For].labeler ==
      AddForwardedHeader.Labeler.ObfuscatedStatic("bigfoot"))
  }

  test("test by=illegal fails") {
    val yaml =
      s"""|by: {kind: illegal}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val _ = intercept[InvalidTypeIdException] {
      mapper.readValue[AddForwardedHeaderConfig](yaml)
    }
  }

  test("test for=illegal fails") {
    val yaml =
      s"""|for: {kind: illegal}
          |""".stripMargin
    val mapper = Parser.objectMapper(yaml, Nil)
    val _ = intercept[InvalidTypeIdException] {
      mapper.readValue[AddForwardedHeaderConfig](yaml)
    }
  }
}
