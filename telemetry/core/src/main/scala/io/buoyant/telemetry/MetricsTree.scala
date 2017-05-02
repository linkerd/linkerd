package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.util.{Duration, Time}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.JavaConverters._

trait MetricsTree {
  def children: Map[String, MetricsTree]
  def resolve(scope: Seq[String]): MetricsTree

  def metric: Metric

  def mkCounter(): Metric.Counter
  def mkStat(): Metric.Stat

  def registerGauge(f: => Float): Unit
  def deregisterGauge(): Unit

  def expireIfIdle(ttl: Duration): Unit
  def prune(): Unit
}

object MetricsTree {

  def apply(): MetricsTree = new Impl

  implicit val param = new Stack.Param[MetricsTree] {
    override def default: MetricsTree = MetricsTree()
  }

  private[this] class Impl extends MetricsTree {

    /*
     * Tree
     */

    private[this] val trees = new ConcurrentHashMap[String, MetricsTree].asScala
    def children: Map[String, MetricsTree] = trees.toMap

    private[this] def getOrMk(k: String): MetricsTree =
      trees.getOrElse(k, {
        val t = new Impl
        trees.putIfAbsent(k, t).getOrElse(t)
      })

    def resolve(scope: Seq[String]): MetricsTree = scope match {
      case Nil => this
      case Seq(name) => getOrMk(name)
      case Seq(child, rest@_*) => getOrMk(child).resolve(rest)
    }

    /*
     * Metric
     */

    // updates to `metric` are synchronized; reads may not be.
    private[this] val metricRef: AtomicReference[Metric] =
      new AtomicReference(Metric.None)

    def metric: Metric = metricRef.get

    @tailrec final def mkCounter(): Metric.Counter = metricRef.get match {
      case c: Metric.Counter => c
      case Metric.None =>
        val c = new Metric.Counter
        if (metricRef.compareAndSet(Metric.None, c)) c
        else mkCounter()
      case _ =>
        throw new IllegalArgumentException("non-counter metric already exists")
    }

    @tailrec final def mkStat(): Metric.Stat = metricRef.get match {
      case s: Metric.Stat => s
      case Metric.None =>
        val s = new Metric.Stat
        if (metricRef.compareAndSet(Metric.None, s)) s
        else mkStat()
      case _ =>
        throw new IllegalArgumentException("non-stat metric already exists")
    }

    @tailrec final def registerGauge(f: => Float): Unit = {
      val orig = metricRef.get
      orig match {
        case Metric.None | (_: Metric.Gauge) =>
          val g = new Metric.Gauge(f)
          if (!metricRef.compareAndSet(orig, g)) registerGauge(f)
        case _ =>
          throw new IllegalArgumentException("non-gauge metric already exists")
      }
    }

    @tailrec final def deregisterGauge(): Unit = metricRef.get match {
      case Metric.None =>
      case orig: Metric.Gauge =>
        if (!metricRef.compareAndSet(orig, Metric.None)) deregisterGauge()
      case _ =>
        throw new IllegalArgumentException("non-gauge metric already exists")
    }

    def expireIfIdle(ttl: Duration): Unit = {
      if (metric != Metric.None && metric.lastUpdated < Time.now - ttl) {
        metricRef.set(Metric.None)
      }
    }

    def prune(): Unit = {
      val _ = trees.retain {
        case (key, child) =>
          child.metric != Metric.None || child.children.nonEmpty
      }
    }
  }
}
