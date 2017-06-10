package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import io.buoyant.telemetry.istio.IstioTelemeter
import org.scalatest._

class IstioInitializerTest extends FunSuite {

  test("io.l5d.istio telemeter loads with defaults") {
    val yaml =
      """|kind: io.l5d.istio
         |experimental: true
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)
    assert(!config.disabled)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[IstioTelemeter])
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
    val _ = telemeter.run.close
  }

  test("io.l5d.istio telemeter loads") {
    val yaml =
      """|kind: io.l5d.istio
         |experimental: true
         |mixerHost: 127.0.0.1
         |mixerPort: 9091
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)
    assert(!config.disabled)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[IstioTelemeter])
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
    val _ = telemeter.run.close
  }

  test("io.l5d.istio telemeter loads as disabled if experimental not set") {
    val yaml =
      """|kind: io.l5d.istio
         |mixerHost: 127.0.0.1
         |mixerPort: 9091
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)
    assert(config.disabled)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[IstioTelemeter])
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
    val _ = telemeter.run.close
  }

}
