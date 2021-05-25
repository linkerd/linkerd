package io.buoyant.telemetry

import com.twitter.finagle.stats._

class MetricsTreeStatsReceiver(
  val tree: MetricsTree,
  val verbosity: Verbosity = Verbosity.Default
) extends StatsReceiverWithCumulativeGauges {

  val repr: AnyRef = this

  protected[this] def registerGauge(schema: GaugeSchema, f: => Float): Unit =
    tree.resolve(schema.metricBuilder.name).registerGauge(schema.metricBuilder.verbosity, f)

  protected[this] def deregisterGauge(name: Seq[String]): Unit =
    tree.resolve(name).deregisterGauge()

  override def counter(counterVerbosity: Verbosity, name: String*): Counter =
    tree.resolve(name).mkCounter(counterVerbosity)

  override def stat(statVerbosity: Verbosity, name: String*): Stat =
    tree.resolve(name).mkStat(statVerbosity)

  def prune(name: String*): Unit =
    tree.resolve(name).prune()

  override def scope(namespace: String): StatsReceiver =
    new MetricsTreeStatsReceiver(tree.resolve(Seq(namespace)))

  def counter(schema: CounterSchema): Counter =
    counter(verbosity, schema.metricBuilder.name: _*)

  def stat(schema: HistogramSchema): Stat =
    stat(verbosity, schema.metricBuilder.name: _*)
}
