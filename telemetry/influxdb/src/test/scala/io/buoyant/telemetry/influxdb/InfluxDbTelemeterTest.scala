package io.buoyant.telemetry.influxdb

import com.twitter.finagle.http.Request
import com.twitter.finagle.stats.buoyant.Metric
import io.buoyant.telemetry.{MetricsTree, MetricsTreeStatsReceiver}
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

  test("counter with no scope") {
    val (stats, handler) = statsAndHandler
    val counter = stats.counter("bas")
    counter.incr()
    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "root,host=none bas=1\n")
    counter.incr()
    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "root,host=none bas=2\n")
  }

  test("counters with no scope") {
    val (stats, handler) = statsAndHandler
    val counter1 = stats.counter("abc")
    val counter2 = stats.counter("def")
    counter1.incr()
    counter2.incr(2)
    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "root,host=none abc=1,def=2\n")
    counter1.incr()
    counter2.incr(2)
    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "root,host=none abc=2,def=4\n")
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

  test("gauge with no scope") {
    val (stats, handler) = statsAndHandler
    var v = 1.0f
    val gauge = stats.addGauge("bas")(v)
    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "root,host=none bas=1.0\n")
    v = 2.0f
    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "root,host=none bas=2.0\n")
  }

  test("gauges with no scope") {
    val (stats, handler) = statsAndHandler
    var v = 1.0f
    val gauge1 = stats.addGauge("abc")(v)
    val gauge2 = stats.addGauge("def")(v * 2)
    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "root,host=none abc=1.0,def=2.0\n")
    v = 2.0f
    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "root,host=none abc=2.0,def=4.0\n")
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

  test("stat with no scope") {
    val (stats, handler) = statsAndHandler
    val stat = stats.stat("bas")
    val metricsTreeStat =
      stats.tree.resolve(Seq("bas")).metric.asInstanceOf[Metric.Stat]

    // first data point
    stat.add(1.0f)

    // endpoint should return no data before first snapshot
    val rsp0 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp0 == "")

    metricsTreeStat.snapshot()

    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "root,host=none bas_avg=1.0,bas_count=1,bas_max=1,bas_min=1,bas_p50=1,bas_p90=1,bas_p95=1,bas_p99=1,bas_p999=1,bas_p9999=1,bas_sum=1\n")

    // second data point
    stat.add(2.0f)
    metricsTreeStat.snapshot()

    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "root,host=none bas_avg=1.5,bas_count=2,bas_max=2,bas_min=1,bas_p50=1,bas_p90=2,bas_p95=2,bas_p99=2,bas_p999=2,bas_p9999=2,bas_sum=3\n")
  }

  test("stats with no scope") {
    val (stats, handler) = statsAndHandler
    val stat1 = stats.stat("abc")
    val stat2 = stats.stat("def")
    val metricsTreeStat1 =
      stats.tree.resolve(Seq("abc")).metric.asInstanceOf[Metric.Stat]
    val metricsTreeStat2 =
      stats.tree.resolve(Seq("def")).metric.asInstanceOf[Metric.Stat]

    // first data point
    stat1.add(1.0f)
    stat2.add(2.0f)

    // endpoint should return no data before first snapshot
    val rsp0 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp0 == "")

    metricsTreeStat1.snapshot()
    metricsTreeStat2.snapshot()

    val rsp1 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp1 == "root,host=none abc_avg=1.0,abc_count=1,abc_max=1,abc_min=1,abc_p50=1,abc_p90=1,abc_p95=1,abc_p99=1,abc_p999=1,abc_p9999=1,abc_sum=1,def_avg=2.0,def_count=1,def_max=2,def_min=2,def_p50=2,def_p90=2,def_p95=2,def_p99=2,def_p999=2,def_p9999=2,def_sum=2\n")

    // second data point
    stat1.add(2.0f)
    stat2.add(4.0f)
    metricsTreeStat1.snapshot()
    metricsTreeStat2.snapshot()

    val rsp2 = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp2 == "root,host=none abc_avg=1.5,abc_count=2,abc_max=2,abc_min=1,abc_p50=1,abc_p90=2,abc_p95=2,abc_p99=2,abc_p999=2,abc_p9999=2,abc_sum=3,def_avg=3.0,def_count=2,def_max=4,def_min=2,def_p50=2,def_p90=4,def_p95=4,def_p99=4,def_p999=4,def_p9999=4,def_sum=6\n")
  }

  test("path stats are labelled") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "service", "/svc/foo").counter("requests")
    counter.incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp == "rt:service,host=none,rt=incoming,service=/svc/foo requests=1\n")
  }

  test("bound stats are labelled") {
    val (stats, handler) = statsAndHandler
    stats.scope("rt", "incoming", "client", "/#/bar").counter("requests").incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp ==
      "rt:client,client=/#/bar,host=none,rt=incoming requests=1\n")
  }

  test("bound stats with path scope are labelled") {
    val (stats, handler) = statsAndHandler
    stats.scope("rt", "incoming", "client", "/#/bar", "service", "/svc/foo").counter("requests").incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp ==
      "rt:client:service,client=/#/bar,host=none,rt=incoming,service=/svc/foo requests=1\n")
  }

  test("server stats are labelled") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "server", "127.0.0.1/4141").counter("requests")
    counter.incr()
    val rsp = await(handler(Request("/admin/metrics/influxdb"))).contentString
    assert(rsp == "rt:server,host=none,rt=incoming,server=127.0.0.1/4141 requests=1\n")
  }
}
