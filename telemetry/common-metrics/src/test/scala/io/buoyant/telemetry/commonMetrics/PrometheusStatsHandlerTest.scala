package io.buoyant.telemetry.commonMetrics

import org.scalatest.FunSuite

class PrometheusStatsHandlerTest extends FunSuite {

  test("Produce prometheus compatible keys") {
    val key = PrometheusStatsHandler.formatKey("foo/bar#baz$abc123:go zoo=default/size")
    assert(key == "foo:bar_baz_abc123:go_zoo_default:size")
  }

  test("Extract histogram labels from the key") {
    val labels = Seq("count", "sum", "avg", "min", "max", "stddev", "p50", "p90", "p95", "p99", "p9990", "p9999")
    for (label <- labels) {
      val key = PrometheusStatsHandler.formatKey(s"rt/http/srv/0.0.0.0/12345/handletime_us.${label}")
      assert(key == s"""rt:http:srv:0_0_0_0:12345:handletime_us{stat=\"${label}\"}""")
    }
  }

  test("Does not extract non-histogram labels from the key") {
    val key = PrometheusStatsHandler.formatKey(s"rt/http/srv/0.0.0.0/12345/handletime_us.custom")
    assert(key == s"rt:http:srv:0_0_0_0:12345:handletime_us_custom")
  }

  test("Preserve non-histogram stats") {
    val key = PrometheusStatsHandler.formatKey("fd_count")
    assert(key == "fd_count")
  }
}
