package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.stats.{DefaultStatsReceiver, InMemoryStatsReceiver}
import com.twitter.finagle.tracing.{BufferingTracer, NullTracer}
import com.twitter.finagle.util.LoadService
import com.twitter.util._
import io.buoyant.config.Parser
import org.scalatest._

class DefaultInitializerTest extends FunSuite {

  test("io.l5d.default telemeter loads") {
    val yaml =
      """|kind: io.l5d.default
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(!telemeter.stats.isNull)
    assert(!telemeter.tracer.isNull)
  }

  test("io.l5d.default telemeter: tracing=false") {
    val yaml =
      """|kind: io.l5d.default
         |tracing: false
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(!telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }

  test("io.l5d.default telemeter: stats=false") {
    val yaml =
      """|kind: io.l5d.default
         |stats: false
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(!telemeter.tracer.isNull)
  }

  test("io.l5d.default telemeter: tracing=false, stats=false") {
    val yaml =
      """|kind: io.l5d.default
         |stats: false
         |tracing: false
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }
}
