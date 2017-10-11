package io.buoyant.telemetry.prometheus

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.telemetry.{TelemeterConfig, TelemeterInitializer}
import io.buoyant.test.FunSuite

class PrometheusTelemeterInitializerTest extends FunSuite {

  test("io.l5d.prometheus telemeter loads") {
    val yaml =
      """|kind: io.l5d.prometheus
         |path: /metrics
         |prefix: some_prefix_
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[PrometheusTelemeter])
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }

  test("io.l5d.prometheus telemeter default path") {
    val yaml =
      """|kind: io.l5d.prometheus
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[PrometheusTelemeter]
    assert(telemeter.handlerPath === "/admin/metrics/prometheus")
  }

  test("io.l5d.prometheus telemeter path") {
    val yaml =
      """|kind: io.l5d.prometheus
         |path: /some/path
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[PrometheusTelemeter]
    assert(telemeter.handlerPath === "/some/path")
  }

  test("io.l5d.prometheus telemeter default prefix") {
    val yaml =
      """|kind: io.l5d.prometheus
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[PrometheusTelemeter]
    assert(telemeter.handlerPrefix === "")
  }

  test("io.l5d.prometheus telemeter prefix") {
    val yaml =
      """|kind: io.l5d.prometheus
         |prefix: some_prefix_
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty).asInstanceOf[PrometheusTelemeter]
    assert(telemeter.handlerPrefix === "some_prefix_")
  }
}
