package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import org.scalatest._

class ZipkinInitializerTest extends FunSuite {

  test("io.l5d.zipkin telemeter loads") {
    val yaml =
      """|kind: io.l5d.zipkin
         |sampleRate: 0.02
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(!telemeter.tracer.isNull)
  }

  test("io.l5d.zipkin telemeter loads, with host and port") {
    val yaml =
      """|kind: io.l5d.zipkin
         |host: 127.0.0.1
         |port: 9411
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(!telemeter.tracer.isNull)
  }
}
