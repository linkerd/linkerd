package io.buoyant.telemetry

import com.twitter.finagle.{Stack, http}
import com.twitter.finagle.stats.{DefaultStatsReceiver, InMemoryStatsReceiver}
import com.twitter.finagle.tracing.{BufferingTracer, NullTracer}
import com.twitter.finagle.util.LoadService
import com.twitter.util._
import io.buoyant.config.Parser
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TwitterMetricsInitializerTest extends FunSuite with Awaits {

  val yaml =
    """|kind: io.l5d.twittermetrics
       |""".stripMargin

  def load() = Parser.objectMapper(yaml, Seq(LoadService[TelemeterInitializer]))
    .readValue[TelemeterConfig](yaml)
    .mk(Stack.Params.empty)

  test("io.l5d.twittermetrics telemeter loads") {
    val telemeter = load()
    assert(!telemeter.stats.isNull)
    assert(telemeter.tracer.isNull)
    assert(telemeter.adminRoutes.nonEmpty)
  }

  test("io.l5d.twittermetrics: GET /admin/metrics") {
    val telemeter = load()
    val muxer = telemeter.adminRoutes.foldLeft(new http.HttpMuxer) {
      case (muxer, (path, handler)) => muxer.withHandler(path, handler)
    }
    val req = http.Request(http.Method.Get, "/admin/metrics")
    val rsp = await(muxer(req))
    assert(rsp.status == http.Status.Ok)
  }

  test("io.l5d.twittermetrics: POST /admin/metrics") {
    val telemeter = load()
    val muxer = telemeter.adminRoutes.foldLeft(new http.HttpMuxer) {
      case (muxer, (path, handler)) => muxer.withHandler(path, handler)
    }
    val req = http.Request(http.Method.Post, "/admin/metrics")
    val rsp = await(muxer(req))
    assert(rsp.status == http.Status.Ok)
  }
}
