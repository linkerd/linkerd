package com.twitter.finagle.stats.buoyant

import com.twitter.finagle.stats.{BucketedHistogram => MetricsBucketedHistogram}

class BucketedHistogram(limits: Array[Int])
  extends MetricsBucketedHistogram(limits)

object BucketedHistogram {

  // Copied directly from com.twitter.finagle.stats.BucketedHistogram

  /**
   * Given an error, compute all the bucket values from 1 until we run out of positive
   * 32-bit ints. The error should be in percent, between 0.0 and 1.0.
   *
   * Each value in the returned array will be at most `1 + (2 * error)` larger than
   * the previous (before rounding).
   *
   * Because percentiles are then computed as the midpoint between two adjacent limits,
   * this means that a value can be at most `1 + error` percent off of the actual
   * percentile.
   *
   * The last bucket tracks up to `Int.MaxValue`.
   */
  private[this] def makeLimitsFor(error: Double): Array[Int] = {
    def build(maxValue: Double, factor: Double, n: Double): Stream[Double] = {
      val next = n * factor
      if (next >= maxValue)
        Stream.empty
      else
        Stream.cons(next, build(maxValue, factor, next))
    }
    require(error > 0.0 && error <= 1.0, error)

    val values = build(Int.MaxValue.toDouble, 1.0 + (error * 2), 1.0)
      .map(_.toInt + 1) // this ensures that the smallest value is 2 (below we prepend `1`)
      .distinct
      .force
    (Seq(1) ++ values).toArray
  }

  // 0.5% error => 1797 buckets, 7188 bytes, max 11 compares on binary search
  private val DefaultErrorPercent = 0.005

  private val DefaultLimits: Array[Int] =
    makeLimitsFor(DefaultErrorPercent)

  def apply(): BucketedHistogram =
    new BucketedHistogram(DefaultLimits)
}
