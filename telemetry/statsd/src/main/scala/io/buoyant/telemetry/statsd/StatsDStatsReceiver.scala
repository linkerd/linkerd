package io.buoyant.telemetry.statsd

import java.util.concurrent.ConcurrentHashMap

import com.timgroup.statsd.StatsDClient
import com.twitter.finagle.stats.{
  Counter,
  CounterSchema,
  HistogramSchema,
  Stat,
  StatsReceiverWithCumulativeGauges,
  Verbosity
}

import scala.jdk.CollectionConverters._
import io.buoyant.telemetry.utils._

private[telemetry] class StatsDStatsReceiver(
  statsDClient: StatsDClient,
  sampleRate: Double
) extends StatsReceiverWithCumulativeGauges {

  val repr: AnyRef = this

  private[statsd] def flush(): Unit = {
    gauges.values.asScala.foreach(_.send)
  }
  private[statsd] def close(): Unit = statsDClient.stop()

  private[this] val counters = new ConcurrentHashMap[String, Metric.Counter]
  private[this] val gauges = new ConcurrentHashMap[String, Metric.Gauge]
  private[this] val stats = new ConcurrentHashMap[String, Metric.Stat]

  protected[this] def registerGauge(verbosity: Verbosity, name: Seq[String], f: => Float): Unit = {
    deregisterGauge(name)

    val statsDName = mkName(name)
    val _ = gauges.put(statsDName, new Metric.Gauge(statsDClient, statsDName, f))
  }

  protected[this] def deregisterGauge(name: Seq[String]): Unit = {
    val _ = gauges.remove(mkName(name))
  }

  override def counter(verbosity: Verbosity, name: String*): Counter = {
    val statsDName = mkName(name)
    val newCounter = new Metric.Counter(statsDClient, statsDName, sampleRate)
    val counter = counters.putIfAbsent(statsDName, newCounter)
    if (counter != null) counter else newCounter
  }

  override def stat(verbosity: Verbosity, name: String*): Stat = {
    val statsDName = mkName(name)
    val newStat = new Metric.Stat(statsDClient, statsDName, sampleRate)
    val stat = stats.putIfAbsent(statsDName, newStat)
    if (stat != null) stat else newStat
  }

  def counter(schema: CounterSchema): Counter = {
    counter(Verbosity.Default, mkName(schema.metricBuilder.name))
  }

  def stat(schema: HistogramSchema): Stat = {
    stat(Verbosity.Default, mkName(schema.metricBuilder.name))
  }
}
