package io.buoyant.telemetry.admin

import com.twitter.conversions.DurationOps._
import com.twitter.finagle.http.Request
import com.twitter.util.{MockTimer, Time}
import io.buoyant.telemetry.{MetricsTree, MetricsTreeStatsReceiver}
import io.buoyant.test.FunSuite

class AdminMetricsExportTelemeterTest extends FunSuite {

  test("counters are updated immediately") {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val timer = new MockTimer
    val telemeter = new AdminMetricsExportTelemeter(metrics, 1.minute, timer)
    val handler = telemeter.handler

    Time.withCurrentTimeFrozen { tc =>
      val counter = stats.scope("foo", "bar").counter("bas")
      counter.incr()
      val rsp1 = await(handler(Request("/admin/metrics.json"))).contentString
      assert(rsp1 == """{"foo/bar/bas":1}""")
      counter.incr()
      val rsp2 = await(handler(Request("/admin/metrics.json"))).contentString
      assert(rsp2 == """{"foo/bar/bas":2}""")
    }
  }

  test("gauges are updated immediately") {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val timer = new MockTimer
    val telemeter = new AdminMetricsExportTelemeter(metrics, 1.minute, timer)
    val handler = telemeter.handler

    Time.withCurrentTimeFrozen { tc =>
      var v = 1.0f
      val gauge = stats.scope("foo", "bar").addGauge("bas") { v }
      val rsp1 = await(handler(Request("/admin/metrics.json"))).contentString
      assert(rsp1 == """{"foo/bar/bas":1.0}""")
      v = 2.0f
      val rsp2 = await(handler(Request("/admin/metrics.json"))).contentString
      assert(rsp2 == """{"foo/bar/bas":2.0}""")
    }
  }

  test("histograms are snapshotted periodically") {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val timer = new MockTimer
    val telemeter = new AdminMetricsExportTelemeter(metrics, 1.minute, timer)
    val handler = telemeter.handler
    val closable = telemeter.run()

    try {
      Time.withTimeAt(Time.now) { tc =>
        val stat = stats.scope("foo", "bar").stat("bas")
        // stat added; not yet snapshotted
        stat.add(1.0f)
        val rsp1 = await(handler(Request("/admin/metrics.json"))).contentString
        assert(rsp1 == """{}""")

        // snapshot
        tc.advance(1.minute)
        timer.tick()

        val rsp2 = await(handler(Request("/admin/metrics.json"))).contentString
        assert(rsp2 == mkHistoJson("foo/bar/bas", 1L))
        stat.add(2.0f)
        // value served only reflects previous snapshot
        val rsp3 = await(handler(Request("/admin/metrics.json"))).contentString
        assert(rsp3 == mkHistoJson("foo/bar/bas", 1L))

        // snapshot
        tc.advance(1.minute)
        timer.tick()

        // value served from previous snapshot
        val rsp4 = await(handler(Request("/admin/metrics.json"))).contentString
        assert(rsp4 == mkHistoJson("foo/bar/bas", 2L))
      }
    } finally {
      val _ = closable.close()
    }
  }

  test("tree mode") {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val timer = new MockTimer
    val telemeter = new AdminMetricsExportTelemeter(metrics, 1.minute, timer)
    val handler = telemeter.handler

    val counter = stats.scope("foo", "bar").counter("bas")
    counter.incr()
    val rsp = await(handler(Request("/admin/metrics.json?tree=1"))).contentString
    assert(rsp == """{"foo":{"bar":{"bas":{"counter":1}}}}""")
  }

  test("subtree selector") {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val timer = new MockTimer
    val telemeter = new AdminMetricsExportTelemeter(metrics, 1.minute, timer)
    val handler = telemeter.handler

    stats.scope("foo", "bar").counter("bas").incr()
    stats.scope("foo", "bar").counter("bass").incr()
    stats.scope("x", "y").counter("z").incr()
    val rsp = await(handler(Request("/admin/metrics.json?q=foo/bar"))).contentString
    assert(rsp == """{"bass":1,"bas":1}""")
  }

  test("subtree selector in tree mode") {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val timer = new MockTimer
    val telemeter = new AdminMetricsExportTelemeter(metrics, 1.minute, timer)
    val handler = telemeter.handler

    stats.scope("foo", "bar").counter("bas").incr()
    stats.scope("foo", "bar").counter("bass").incr()
    stats.scope("x", "y").counter("z").incr()
    val rsp = await(handler(Request("/admin/metrics.json?q=foo/bar&tree=1"))).contentString
    assert(rsp == """{"bass":{"counter":1},"bas":{"counter":1}}""")
  }

  private[this] def mkHistoJson(name: String, datum: Long): String =
    Seq(
      s""""$name.count":1""",
      s""""$name.max":$datum""",
      s""""$name.min":$datum""",
      s""""$name.p50":$datum""",
      s""""$name.p90":$datum""",
      s""""$name.p95":$datum""",
      s""""$name.p99":$datum""",
      s""""$name.p9990":$datum""",
      s""""$name.p9999":$datum""",
      s""""$name.sum":$datum""",
      s""""$name.avg":$datum.0"""
    ).mkString("{", ",", "}")
}
