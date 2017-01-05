package io.buoyant.telemetry.statsd

import com.timgroup.statsd.StatsDClient

class MockStatsDClient extends StatsDClient {
  var lastName = ""
  var lastValue = ""
  var stopped = false

  def count(aspect: String, delta: Long, sampleRate: Double, tags: String*): Unit = {
    lastName = aspect
    lastValue = delta.toString
  }

  def recordExecutionTime(aspect: String, timeInMs: Long, sampleRate: Double, tags: String*): Unit = {
    lastName = aspect
    lastValue = timeInMs.toString
  }

  def recordGaugeValue(aspect: String, value: Double, tags: String*): Unit = {
    lastName = aspect
    lastValue = value.toString
  }

  def stop(): Unit = { stopped = true }

  // we can't simply extend NoOpStatsDClient because it's final
  def count(x$1: String, x$2: Long, x$3: String*): Unit = ???
  def decrement(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def decrement(x$1: String, x$2: String*): Unit = ???
  def decrementCounter(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def decrementCounter(x$1: String, x$2: String*): Unit = ???
  def gauge(x$1: String, x$2: Long, x$3: Double, x$4: String*): Unit = ???
  def gauge(x$1: String, x$2: Long, x$3: String*): Unit = ???
  def gauge(x$1: String, x$2: Double, x$3: Double, x$4: String*): Unit = ???
  def gauge(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def histogram(x$1: String, x$2: Long, x$3: Double, x$4: String*): Unit = ???
  def histogram(x$1: String, x$2: Long, x$3: String*): Unit = ???
  def histogram(x$1: String, x$2: Double, x$3: Double, x$4: String*): Unit = ???
  def histogram(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def increment(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def increment(x$1: String, x$2: String*): Unit = ???
  def incrementCounter(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def incrementCounter(x$1: String, x$2: String*): Unit = ???
  def recordEvent(x$1: com.timgroup.statsd.Event, x$2: String*): Unit = ???
  def recordExecutionTime(x$1: String, x$2: Long, x$3: String*): Unit = ???
  def recordGaugeValue(x$1: String, x$2: Long, x$3: Double, x$4: String*): Unit = ???
  def recordGaugeValue(x$1: String, x$2: Long, x$3: String*): Unit = ???
  def recordGaugeValue(x$1: String, x$2: Double, x$3: Double, x$4: String*): Unit = ???
  def recordHistogramValue(x$1: String, x$2: Long, x$3: Double, x$4: String*): Unit = ???
  def recordHistogramValue(x$1: String, x$2: Long, x$3: String*): Unit = ???
  def recordHistogramValue(x$1: String, x$2: Double, x$3: Double, x$4: String*): Unit = ???
  def recordHistogramValue(x$1: String, x$2: Double, x$3: String*): Unit = ???
  def recordServiceCheckRun(x$1: com.timgroup.statsd.ServiceCheck): Unit = ???
  def recordSetValue(x$1: String, x$2: String, x$3: String*): Unit = ???
  def serviceCheck(x$1: com.timgroup.statsd.ServiceCheck): Unit = ???
  def time(x$1: String, x$2: Long, x$3: Double, x$4: String*): Unit = ???
  def time(x$1: String, x$2: Long, x$3: String*): Unit = ???
}
