package io.buoyant.telemetry.statsd

import org.scalatest._

class MetricTest extends FunSuite {

  test("Counter increments a statsd counter") {
    val name = "foo"
    val value = 123
    val statsDClient = new MockStatsDClient
    val counter = new Metric.Counter(statsDClient, name, 1.0d)
    counter.incr(value)

    assert(statsDClient.lastName == name)
    assert(statsDClient.lastValue == value.toString)
  }

  test("Stat records a statsd execution time") {
    val name = "foo"
    val value = 123.4F
    val statsDClient = new MockStatsDClient
    val stat = new Metric.Stat(statsDClient, name, 1.0d)
    stat.add(value)

    assert(statsDClient.lastName == name)
    assert(statsDClient.lastValue == value.toLong.toString)
  }

  test("Gauge records a statsd gauge value on every send") {
    val name = "foo"
    var value = 123.4F
    def func(): Float = { value += value; value }
    val statsDClient = new MockStatsDClient
    val gauge = new Metric.Gauge(statsDClient, name, func)

    gauge.send
    assert(statsDClient.lastName == name)
    assert(statsDClient.lastValue == value.toDouble.toString)

    gauge.send
    assert(statsDClient.lastName == name)
    assert(statsDClient.lastValue == value.toDouble.toString)
  }
}
