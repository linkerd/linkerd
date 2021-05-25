package com.twitter.finagle.stats.buoyant

import com.twitter.finagle.stats.{BucketAndCount, BucketedHistogram, Metadata, NoMetadata, Counter => FCounter, Stat => FStat}
import com.twitter.util.{Duration, Time}
import java.util.concurrent.atomic.AtomicLong

sealed trait Metric

object Metric {

  object None extends Metric

  class Counter extends FCounter with Metric {
    private[this] val value = new AtomicLong
    def incr(delta: Long): Unit = {
      val _ = value.getAndAdd(delta)
    }
    def get: Long = value.get

    def metadata: Metadata = NoMetadata
  }

  class Stat extends FStat with Metric {
    // Access must be synchronized
    private[this] val underlying = BucketedHistogram()
    private[this] var summarySnapshot: HistogramSummary = null

    private[this] var resetTime = Time.now
    def startingAt: Time = resetTime

    def add(value: Float): Unit = underlying.synchronized {
      // TODO track update time to allow detection of stale stats.
      underlying.add(value.toLong)
    }

    def peek: Seq[BucketAndCount] = underlying.synchronized {
      underlying.bucketAndCounts
    }

    def snapshot(): HistogramSummary = underlying.synchronized {
      summarySnapshot = summary
      summarySnapshot
    }

    def reset(): (Seq[BucketAndCount], Duration) = underlying.synchronized {
      val buckets = underlying.bucketAndCounts
      underlying.clear()
      val now = Time.now
      val delta = now - resetTime
      resetTime = now
      (buckets, delta)
    }

    def summary: HistogramSummary = underlying.synchronized {
      HistogramSummary(
        underlying.count,
        underlying.minimum,
        underlying.maximum,
        underlying.sum,
        underlying.percentile(0.50),
        underlying.percentile(0.90),
        underlying.percentile(0.95),
        underlying.percentile(0.99),
        underlying.percentile(0.999),
        underlying.percentile(0.9999),
        underlying.average
      )
    }

    def snapshottedSummary: HistogramSummary = summarySnapshot

    def metadata: Metadata = NoMetadata
  }

  class Gauge(f: => Float) extends Metric {
    def get: Float = f
  }

  case class HistogramSummary(
    count: Long,
    min: Long,
    max: Long,
    sum: Long,
    p50: Long,
    p90: Long,
    p95: Long,
    p99: Long,
    p9990: Long,
    p9999: Long,
    avg: Double
  )
}
