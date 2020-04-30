package io.buoyant.telemetry.statsd

import io.buoyant.telemetry.utils._
import com.timgroup.statsd.NoOpStatsDClient
import org.scalatest._

class StatsDStatsReceiverTest extends FunSuite {

  test("creates a stats receiver") {
    val stats = new StatsDStatsReceiver(new NoOpStatsDClient, 1.0d)
    assert(stats.isInstanceOf[StatsDStatsReceiver])
  }

  test("stops StatsDClient on close") {
    val statsDClient = new MockStatsDClient
    val stats = new StatsDStatsReceiver(statsDClient, 1.0d)
    assert(!statsDClient.stopped)
    stats.close()
    assert(statsDClient.stopped)
  }

  test("metric names are correctly rewritten") {
    val names = Seq(
      Seq("foo", "$", "", "bar/baz.stuff#word?who//what^when*where\\why", "#/^//\\huh?@$%^&"),
      Seq("clnt", "zipkin-tracer", "service_creation", "service_acquisition_latency_ms"),
      Seq("rt", "http", "client", "#/io.l5d.fs/default/path/http/1.1/GET/default", "request_latency_ms")
    )
    val newNames = names.map { mkName(_) }
    val expected = Seq(
      "foo._.bar.baz_stuff_word_who.what_when_where_why._._._huh______",
      "clnt.zipkin_tracer.service_creation.service_acquisition_latency_ms",
      "rt.http.client._.io_l5d_fs.default.path.http.1_1.GET.default.request_latency_ms"
    )

    assert(newNames == expected)
  }

}
