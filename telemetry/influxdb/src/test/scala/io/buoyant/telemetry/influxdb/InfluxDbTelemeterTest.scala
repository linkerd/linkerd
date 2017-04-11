package io.buoyant.telemetry.influxdb

import com.twitter.conversions.time._
import com.twitter.finagle.http.Request
import com.twitter.util.{MockTimer, Time}
import io.buoyant.telemetry.{Metric, MetricsTree, MetricsTreeStatsReceiver}
import io.buoyant.test.FunSuite

class InfluxDbTelemeterTest extends FunSuite {

  def statsAndHandler = {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val telemeter = new InfluxDbTelemeter(metrics)
    val handler = telemeter.handler
    (stats, handler)
  }

  test("counter") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("foo", "bar").counter("bas")
    counter.incr()
    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "foo:bar,host=none bas=1\n")
    counter.incr()
    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "foo:bar,host=none bas=2\n")
  }

  test("gauge") {
    val (stats, handler) = statsAndHandler
    var v = 1.0f
    val gauge = stats.scope("foo", "bar").addGauge("bas")(v)
    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "foo:bar,host=none bas=1.0\n")
    v = 2.0f
    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "foo:bar,host=none bas=2.0\n")
  }

  test("stat") {
    val (stats, handler) = statsAndHandler
    val stat = stats.scope("foo", "bar").stat("bas")
    val metricsTreeStat =
      stats.tree.resolve(Seq("foo", "bar", "bas")).metric.asInstanceOf[Metric.Stat]

    // first data point
    stat.add(1.0f)

    // endpoint should return no data before first snapshot
    val rsp0 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp0 == "")

    metricsTreeStat.snapshot()

    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "foo:bar,host=none bas_avg=1.0,bas_count=1,bas_max=1,bas_min=1,bas_p50=1,bas_p90=1,bas_p95=1,bas_p99=1,bas_p999=1,bas_p9999=1,bas_sum=1\n")

    // second data point
    stat.add(2.0f)
    metricsTreeStat.snapshot()

    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "foo:bar,host=none bas_avg=1.5,bas_count=2,bas_max=2,bas_min=1,bas_p50=1,bas_p90=2,bas_p95=2,bas_p99=2,bas_p999=2,bas_p9999=2,bas_sum=3\n")
  }

  test("path stats are labelled") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "dst", "path", "/svc/foo").counter("requests")
    counter.incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp == "rt:dst_path,dst_path=/svc/foo,host=none,rt=incoming requests=1\n")
  }

  test("bound stats are labelled") {
    val (stats, handler) = statsAndHandler
    stats.scope("rt", "incoming", "dst", "id", "/#/bar").counter("requests").incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp ==
      "rt:dst_id,dst_id=/#/bar,host=none,rt=incoming requests=1\n")
  }

  test("bound stats with path scope are labelled") {
    val (stats, handler) = statsAndHandler
    stats.scope("rt", "incoming", "dst", "id", "/#/bar", "path", "/svc/foo").counter("requests").incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp ==
      "rt:dst_id:dst_path,dst_id=/#/bar,dst_path=/svc/foo,host=none,rt=incoming requests=1\n")
  }

  test("server stats are labelled") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "srv", "127.0.0.1/4141").counter("requests")
    counter.incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp == "rt:srv,host=none,rt=incoming,srv=127.0.0.1/4141 requests=1\n")
  }
}
