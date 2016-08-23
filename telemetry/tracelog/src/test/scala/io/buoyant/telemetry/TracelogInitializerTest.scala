package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import com.twitter.util._
import io.buoyant.config.Parser
import org.scalatest._

class TracelogInitializerTest extends FunSuite {

  test("io.l5d.default telemeter loads") {
    val yaml =
      """|kind: io.l5d.tracelog
         |sampleRate: 0.02
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(!telemeter.tracer.isNull)
  }

  test("io.l5d.default telemeter loads, with log level") {
    val yaml =
      """|kind: io.l5d.tracelog
         |level: trace
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(!telemeter.tracer.isNull)
  }

  test("io.l5d.default telemeter loads, with invalid log level") {
    val yaml =
      """|kind: io.l5d.tracelog
         |level: supergood
         |""".stripMargin

    val mapper = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
    val _ = intercept[com.fasterxml.jackson.databind.JsonMappingException] {
      mapper.readValue[TelemeterConfig](yaml)
    }
  }

}
