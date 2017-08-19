package io.buoyant.telemetry

import com.twitter.finagle.stats._

class MetricsTreeStatsReceiver(
  val tree: MetricsTree
) extends StatsReceiverWithCumulativeGauges {

  val repr: AnyRef = this

  protected[this] def registerGauge(verbosity: Verbosity, name: Seq[String], f: => Float): Unit =
    tree.resolve(name).registerGauge(verbosity, f)

  protected[this] def deregisterGauge(name: Seq[String]): Unit =
    tree.resolve(name).deregisterGauge()

  override def counter(verbosity: Verbosity, name: String*): Counter =
    tree.resolve(name).mkCounter(verbosity)

  override def stat(verbosity: Verbosity, name: String*): Stat =
    tree.resolve(name).mkStat(verbosity)

  def prune(name: String*): Unit =
    tree.resolve(name).prune()

  override def scope(namespace: String): StatsReceiver =
    new MetricsTreeStatsReceiver(tree.resolve(Seq(namespace)))
}
