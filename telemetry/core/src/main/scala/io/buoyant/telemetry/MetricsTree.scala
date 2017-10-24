package io.buoyant.telemetry

import com.twitter.finagle.Stack
import com.twitter.finagle.stats.Verbosity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

trait MetricsTree {
  def children: Map[String, MetricsTree]
  def resolve(scope: Seq[String]): MetricsTree
  def tryResolve(scope: Seq[String]): Option[MetricsTree]

  def metric: Metric

  def mkCounter(verbosity: Verbosity): Metric.Counter
  def mkStat(verbosity: Verbosity): Metric.Stat

  def registerGauge(verbosity: Verbosity, f: => Float): Unit
  def deregisterGauge(): Unit

  /**
   * Recurrsively remove all Metrics and children from this tree.  This is not
   * threadsafe and explicit synchronization must be used to avoid holding
   * references to Metrics in pruned branches.
   */
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

    def tryResolve(scope: Seq[String]): Option[MetricsTree] = scope match {
      case Nil => Some(this)
      case Seq(name) => trees.get(name)
      case Seq(child, rest@_*) => trees.get(child).flatMap(_.tryResolve(rest))
    }

    /*
     * Metric
     */

    // updates to `metric` are synchronized; reads may not be.
    private[this] val metricRef: AtomicReference[Metric] =
      new AtomicReference(Metric.None)

    def metric: Metric = metricRef.get

    @tailrec final def mkCounter(verbosity: Verbosity): Metric.Counter = metricRef.get match {
      case c: Metric.Counter => c
      case Metric.None =>
        val c = new Metric.Counter
        if (metricRef.compareAndSet(Metric.None, c)) c
        else mkCounter(verbosity)
      case _ =>
        throw new IllegalArgumentException("non-counter metric already exists")
    }

    @tailrec final def mkStat(verbosity: Verbosity): Metric.Stat = metricRef.get match {
      case s: Metric.Stat => s
      case Metric.None =>
        val s = new Metric.Stat
        if (metricRef.compareAndSet(Metric.None, s)) s
        else mkStat(verbosity)
      case _ =>
        throw new IllegalArgumentException("non-stat metric already exists")
    }

    @tailrec final def registerGauge(verbosity: Verbosity, f: => Float): Unit = {
      val orig = metricRef.get
      orig match {
        case Metric.None | (_: Metric.Gauge) =>
          val g = new Metric.Gauge(f)
          if (!metricRef.compareAndSet(orig, g)) registerGauge(verbosity, f)
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

    def prune(): Unit = {
      trees.values.foreach { child =>
        child.prune()
      }
      trees.clear()
      metricRef.set(Metric.None)
    }
  }

  /**
   * Flatten `tree`, skipping empty scopes.
   */
  def flatten(
    tree: MetricsTree,
    prefix: String = "",
    acc: mutable.Buffer[(String, Metric)] = mutable.Buffer()
  ): Seq[(String, Metric)] = {
    // This method could also have been implemented as
    // `flatten(...).filter(...)` but this seems more performant.
    tree.metric match {
      case Metric.None =>
      case metric => acc += prefix -> tree.metric
    }

    for ((name, child) <- tree.children) {
      if (prefix.isEmpty)
        flatten(child, name, acc)
      else
        flatten(child, s"$prefix/$name", acc)
    }
    acc
  }
}
