package io.buoyant.telemetry.prometheus

import com.twitter.finagle.http.Request
import com.twitter.finagle.stats.buoyant.Metric
import io.buoyant.telemetry.{MetricsTree, MetricsTreeStatsReceiver}
import io.buoyant.test.FunSuite
import jdk.nashorn.internal.ir.RuntimeNode

class PrometheusTelemeterTest extends FunSuite {

  private val prometheusPath = "/admin/metrics/prometheus"
  private val prometheusPrefix = "linkerd_"

  def statsAndHandler = {
    val metrics = MetricsTree()
    val stats = new MetricsTreeStatsReceiver(metrics)
    val telemeter = new PrometheusTelemeter(metrics, prometheusPath, prometheusPrefix)
    val handler = telemeter.handler
    (stats, handler)
  }

  test("counter") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("foo", "bar").counter("bas")
    counter.incr()
    val rsp1 = await(handler(Request(prometheusPath))).contentString
    assert(rsp1 == "linkerd_foo:bar:bas 1\n")
    counter.incr()
    val rsp2 = await(handler(Request(prometheusPath))).contentString
    assert(rsp2 == "linkerd_foo:bar:bas 2\n")
  }

  test("gauge") {
    val (stats, handler) = statsAndHandler
    var v = 1.0f
    val gauge = stats.scope("foo", "bar").addGauge("bas")(v)
    val rsp1 = await(handler(Request(prometheusPath))).contentString
    assert(rsp1 == "linkerd_foo:bar:bas 1.0\n")
    v = 2.0f
    val rsp2 = await(handler(Request(prometheusPath))).contentString
    assert(rsp2 == "linkerd_foo:bar:bas 2.0\n")
  }

  test("stat") {
    val (stats, handler) = statsAndHandler
    val stat = stats.scope("foo", "bar").stat("bas")
    val metricsTreeStat =
      stats.tree.resolve(Seq("foo", "bar", "bas")).metric.asInstanceOf[Metric.Stat]

    // first data point
    stat.add(1.0f)

    // endpoint should return no data before first snapshot
    val rsp0 = await(handler(Request(prometheusPath))).contentString
    assert(rsp0 == "")

    metricsTreeStat.snapshot()

    val rsp1 = await(handler(Request(prometheusPath))).contentString
    assert(rsp1 == """linkerd_foo:bar:bas_count 1
                     |linkerd_foo:bar:bas_sum 1
                     |linkerd_foo:bar:bas_avg 1.0
                     |linkerd_foo:bar:bas{quantile="0"} 1
                     |linkerd_foo:bar:bas{quantile="0.5"} 1
                     |linkerd_foo:bar:bas{quantile="0.9"} 1
                     |linkerd_foo:bar:bas{quantile="0.95"} 1
                     |linkerd_foo:bar:bas{quantile="0.99"} 1
                     |linkerd_foo:bar:bas{quantile="0.999"} 1
                     |linkerd_foo:bar:bas{quantile="0.9999"} 1
                     |linkerd_foo:bar:bas{quantile="1"} 1
                     |""".stripMargin)

    // second data point
    stat.add(2.0f)
    metricsTreeStat.snapshot()

    val rsp2 = await(handler(Request(prometheusPath))).contentString
    assert(rsp2 == """linkerd_foo:bar:bas_count 2
                     |linkerd_foo:bar:bas_sum 3
                     |linkerd_foo:bar:bas_avg 1.5
                     |linkerd_foo:bar:bas{quantile="0"} 1
                     |linkerd_foo:bar:bas{quantile="0.5"} 1
                     |linkerd_foo:bar:bas{quantile="0.9"} 2
                     |linkerd_foo:bar:bas{quantile="0.95"} 2
                     |linkerd_foo:bar:bas{quantile="0.99"} 2
                     |linkerd_foo:bar:bas{quantile="0.999"} 2
                     |linkerd_foo:bar:bas{quantile="0.9999"} 2
                     |linkerd_foo:bar:bas{quantile="1"} 2
                     |""".stripMargin)
  }

  test("empty histograms are not printed") {
    val (stats, handler) = statsAndHandler
    val stat = stats.scope("foo", "bar").stat("bas")
    val metricsTreeStat =
      stats.tree.resolve(Seq("foo", "bar", "bas")).metric.asInstanceOf[Metric.Stat]

    // endpoint should return no data before first snapshot
    val rsp0 = await(handler(Request(prometheusPath))).contentString
    assert(rsp0 == "")

    metricsTreeStat.snapshot()

    val rsp1 = await(handler(Request(prometheusPath))).contentString
    assert(rsp1 == "")
  }

  test("metric labels are escaped") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "service", """\x5b\x31\x32\x33\x2e\x31\x32\x33\x2e\x31\x32\x33\x2e\x31\x32\x33\x5dun"esc""").counter("requests")
    counter.incr()
    val rsp = await(handler(Request(prometheusPath))).contentString
    assert(rsp == """linkerd_rt:service:requests{rt="incoming", service="\\x5b\\x31\\x32\\x33\\x2e\\x31\\x32\\x33\\x2e\\x31\\x32\\x33\\x2e\\x31\\x32\\x33\\x5dun\\esc"} 1
""")
  }

  test("path stats are labelled") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "service", "/svc/foo").counter("requests")
    counter.incr()
    val rsp = await(handler(Request(prometheusPath))).contentString
    assert(rsp == "linkerd_rt:service:requests{rt=\"incoming\", service=\"/svc/foo\"} 1\n")
  }

  test("bound stats are labelled") {
    val (stats, handler) = statsAndHandler
    stats.scope("rt", "incoming", "client", "/#/bar").counter("requests").incr()
    val rsp = await(handler(Request(prometheusPath))).contentString
    assert(rsp ==
      "linkerd_rt:client:requests{rt=\"incoming\", client=\"/#/bar\"} 1\n")
  }

  test("bound stats with path scope are labelled") {
    val (stats, handler) = statsAndHandler
    stats.scope("rt", "incoming", "client", "/#/bar", "service", "/svc/foo").counter("requests").incr()
    val rsp = await(handler(Request(prometheusPath))).contentString
    assert(rsp ==
      "linkerd_rt:client:service:requests{rt=\"incoming\", client=\"/#/bar\", service=\"/svc/foo\"} 1\n")
  }

  test("server stats are labelled") {
    val (stats, handler) = statsAndHandler
    val counter = stats.scope("rt", "incoming", "server", "127.0.0.1/4141").counter("requests")
    counter.incr()
    val rsp = await(handler(Request(prometheusPath))).contentString
    assert(rsp == "linkerd_rt:server:requests{rt=\"incoming\", server=\"127.0.0.1/4141\"} 1\n")
  }

  test("exception stats are labelled") {
    val (stats, handler) = statsAndHandler
    // Replicate stack trace for some exception foo:bar:baz
    stats.scope("rt", "incoming", "client", "127.0.0.1/4141", "failures").counter("foo").incr()
    stats.scope("rt", "incoming", "client", "127.0.0.1/4141", "failures").counter("foo:bar").incr()
    stats.scope("rt", "incoming", "client", "127.0.0.1/4141", "failures").counter("foo:bar:baz").incr()

    // Replicate stack trace for some exception foo:bar:qux
    stats.scope("rt", "incoming", "client", "127.0.0.1/4141", "failures").counter("foo").incr()
    stats.scope("rt", "incoming", "client", "127.0.0.1/4141", "failures").counter("foo:bar").incr()
    stats.scope("rt", "incoming", "client", "127.0.0.1/4141", "failures").counter("foo:bar:qux").incr()


    val rsp = await(handler(Request(prometheusPath))).contentString
    assert(rsp == """linkerd_rt:client:failures{rt="incoming", client="127.0.0.1/4141", exception="foo:bar"} 2
                    |linkerd_rt:client:failures{rt="incoming", client="127.0.0.1/4141", exception="foo:bar:baz"} 1
                    |linkerd_rt:client:failures{rt="incoming", client="127.0.0.1/4141", exception="foo"} 2
                    |linkerd_rt:client:failures{rt="incoming", client="127.0.0.1/4141", exception="foo:bar:qux"} 1
                    |""".stripMargin)
  }
}
