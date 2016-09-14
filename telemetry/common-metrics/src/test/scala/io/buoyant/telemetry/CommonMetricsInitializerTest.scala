package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.stats.{DefaultStatsReceiver, InMemoryStatsReceiver}
import com.twitter.finagle.tracing.{BufferingTracer, NullTracer}
import com.twitter.finagle.util.LoadService
import com.twitter.util._
import io.buoyant.admin.Admin
import io.buoyant.config.Parser
import org.scalatest._

class DefaultInitializerTest extends FunSuite {

  test("io.l5d.commonMetrics telemeter loads") {
    val yaml =
      """|kind: io.l5d.commonMetrics
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(!telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
  }

  test("io.l5d.commonMetrics exposes admin endpoints") {
    val yaml =
      """|kind: io.l5d.commonMetrics
         |""".stripMargin

    val config = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
      .readValue[TelemeterConfig](yaml)

    val telemeter = config.mk(Stack.Params.empty)
    assert(telemeter.isInstanceOf[Admin.WithHandlers])
    assert(telemeter.asInstanceOf[Admin.WithHandlers].adminHandlers.size == 3)
  }
}
