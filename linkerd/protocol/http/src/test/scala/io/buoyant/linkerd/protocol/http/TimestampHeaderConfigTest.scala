package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.Stack
import io.buoyant.config.Parser
import io.buoyant.router.RouterLabel
import io.buoyant.router.http.TimestampHeaderFilter
import io.buoyant.test.FunSuite

class TimestampHeaderConfigTest extends FunSuite {

  test("parse empty") {
    val yaml = "{}"
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[TimestampHeaderConfig](yaml)
    val params = (Stack.Params.empty + RouterLabel.Param("rtr")) ++: config
    assert(params[TimestampHeaderFilter.Param] == TimestampHeaderFilter.Param(None))
  }

  test("parse configured") {
    val yaml = "header: x-my-great-header"
    val mapper = Parser.objectMapper(yaml, Nil)
    val config = mapper.readValue[TimestampHeaderConfig](yaml)
    val params = (Stack.Params.empty + RouterLabel.Param("rtr")) ++: config
    assert(params[TimestampHeaderFilter.Param] ==
      TimestampHeaderFilter.Param(Some("x-my-great-header")))
  }
}
