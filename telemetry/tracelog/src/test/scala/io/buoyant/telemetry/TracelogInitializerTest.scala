package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.util.LoadService
import io.buoyant.config.Parser
import org.scalatest._

class TracelogInitializerTest extends FunSuite {

  test("io.l5d.tracelog telemeter loads") {
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

  test("io.l5d.tracelog telemeter accepts int for sampleRate") {
    val yaml =
      """|kind: io.l5d.tracelog
         |sampleRate: 1
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    assert(config.asInstanceOf[TracelogConfig].sampleRate == Some(1.0))
  }

  test("io.l5d.tracelog telemeter loads, with log level") {
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

  test("io.l5d.tracelog telemeter fails with invalid log level") {
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
