package io.buoyant.admin

import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class PrometheusStatsHandlerTest extends FunSuite with Awaits {
  test("Produce prometheus compatible keys") {
    val key = PrometheusStatsHandler.formatKey("foo/bar#baz$abc123:go zoo=default/size")
    assert(key == "foo:bar_baz_abc123:go_zoo_default:size")
  }
  test("Extract labels from the key") {
    val key = PrometheusStatsHandler.formatKey("rt/http/srv/0.0.0.0/12345/handletime_us.max")
    assert(key == "rt:http:srv:0_0_0_0:12345:handletime_us{stat=\"max\"}")
  }
  test("Preserve non-histogram stats") {
    val key = PrometheusStatsHandler.formatKey("fd_count")
    assert(key == "fd_count")
  }
}
