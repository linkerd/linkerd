package io.buoyant.telemetry.influxdb

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.telemetry.{TelemeterConfig, TelemeterInitializer}
import io.buoyant.test.FunSuite

class InfluxDbTelemeterInitializerTest extends FunSuite {

  test("io.l5d.influxdb telemeter loads") {
    val yaml =
      """|kind: io.l5d.influxdb
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[InfluxDbTelemeter])
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }
}
