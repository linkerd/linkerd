package io.buoyant.telemetry

import com.twitter.finagle.stats.{Counter, Stat, StatsReceiverWithCumulativeGauges}

class MetricsTreeStatsReceiver(
  val tree: MetricsTree
) extends StatsReceiverWithCumulativeGauges {

  val repr: AnyRef = this

  protected[this] def registerGauge(name: Seq[String], f: => Float): Unit =
    tree.resolve(name).registerGauge(f)

  protected[this] def deregisterGauge(name: Seq[String]): Unit =
    tree.resolve(name).deregisterGauge()

  def counter(name: String*): Counter =
    tree.resolve(name).mkCounter()

  def stat(name: String*): Stat =
    tree.resolve(name).mkStat()
}
