package io.buoyant.telemetry.statsd

import com.timgroup.statsd.StatsDClient
import com.twitter.finagle.stats.{Metadata, NoMetadata, Counter => FCounter, Stat => FStat}

private[statsd] object Metric {

  // stats (timing/histograms) only send when Math.random() <= sampleRate
  class Counter(statsDClient: StatsDClient, name: String, sampleRate: Double) extends FCounter {
    def incr(delta: Long): Unit = statsDClient.count(name, delta, sampleRate)

    def metadata: Metadata = NoMetadata
  }

  // gauges simply evaluate on send
  class Gauge(statsDClient: StatsDClient, name: String, f: => Float) {
    def send: Unit = statsDClient.recordGaugeValue(name, f)
  }

  // stats (timing/histograms) only send when Math.random() <= sampleRate
  class Stat(statsDClient: StatsDClient, name: String, sampleRate: Double) extends FStat {
    def add(value: Float): Unit =
      // would prefer `recordHistogramValue`, but that's an extension, supported by Datadog and InfluxDB
      statsDClient.recordExecutionTime(name, value.toLong, sampleRate)

    def metadata: Metadata = NoMetadata
  }
}
