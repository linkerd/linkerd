package io.buoyant.linkerd

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.buoyant.TotalTimeout
import com.twitter.finagle.Path
import com.twitter.util.Duration
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite

class SvcTest extends FunSuite {

  def parse(yaml: String): Svc =
    Parser.objectMapper(yaml, Nil).readValue[Svc](yaml)

  test("default applies to all services") {
    val svc = parse("totalTimeoutMs: 500")

    val fooParams = svc.pathParams.paramsFor(Path.read("/svc/foo"))
    assert(fooParams[TotalTimeout.Param].timeout == 500.millis)
    val barParams = svc.pathParams.paramsFor(Path.read("/svc/bar"))
    assert(barParams[TotalTimeout.Param].timeout == 500.millis)
  }

  test("per service config") {
    val svc = parse("""|kind: io.l5d.static
                       |configs:
                       |- prefix: "/svc/foo"
                       |  totalTimeoutMs: 100
                       |- prefix: "/svc/bar"
                       |  totalTimeoutMs: 200""".stripMargin)

    val fooParams = svc.pathParams.paramsFor(Path.read("/svc/foo"))
    assert(fooParams[TotalTimeout.Param].timeout == 100.millis)

    val barParams = svc.pathParams.paramsFor(Path.read("/svc/bar"))
    assert(barParams[TotalTimeout.Param].timeout == 200.millis)

    // bas, not configured, gets default values
    val basParams = svc.pathParams.paramsFor(Path.read("/svc/bas"))
    assert(basParams[TotalTimeout.Param].timeout == Duration.Top)
  }

  test("later client configs override earlier ones") {
    val svc = parse("""|kind: io.l5d.static
                       |configs:
                       |- prefix: "/"
                       |  totalTimeoutMs: 100
                       |- prefix: "/svc/foo"
                       |  totalTimeoutMs: 200""".stripMargin)

    val fooParams = svc.pathParams.paramsFor(Path.read("/svc/foo"))
    assert(fooParams[TotalTimeout.Param].timeout == 200.millis)

    val barParams = svc.pathParams.paramsFor(Path.read("/svc/bar"))
    assert(barParams[TotalTimeout.Param].timeout == 100.millis)
  }
}
