package io.buoyant.telemetry.admin

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import io.buoyant.telemetry.{TelemeterConfig, TelemeterInitializer}
import io.buoyant.test.FunSuite

class AdminMetricsExportTelemeterInitializerTest extends FunSuite {

  test("io.l5d.admin telemeter loads") {
    val yaml =
      """|kind: io.l5d.adminMetricsExport
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }

  test("io.l5d.admin exposes metrics endpoint") {
    val yaml =
      """|kind: io.l5d.adminMetricsExport
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    val handlers = telemeter.asInstanceOf[Admin.WithHandlers].adminHandlers
    assert(handlers.size == 1)
    assert(handlers.head.url == "/admin/metrics.json")
  }
}
