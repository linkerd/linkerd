package com.twitter.finagle.stats.buoyant

import com.twitter.finagle.stats.BucketAndCount

import org.junit.runner.RunWith
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, FunSuite}
import org.scalatest.junit.JUnitRunner

// Originally copied from
// finagle/finagle-stats/src/test/scala/com/twitter/finagle/stats/BucketedHistogramTest.scala

@RunWith(classOf[JUnitRunner])
class MetricsBucketedHistogramTest extends FunSuite
  with GeneratorDrivenPropertyChecks
  with Matchers {
  test("constructor limits cannot be empty") {
    intercept[IllegalArgumentException] { new MetricsBucketedHistogram(new Array[Int](0)) }
  }

  test("constructor limits cannot have negative values") {
    intercept[IllegalArgumentException] { new MetricsBucketedHistogram(Array[Int](-1)) }
  }

  test("constructor limits must be increasing in value") {
    intercept[IllegalArgumentException] { new MetricsBucketedHistogram(Array[Int](0, 0)) }
  }

  test("percentile when empty") {
    val h = MetricsBucketedHistogram()
    assert(h.percentile(0.0) == 0)
    assert(h.percentile(0.5) == 0)
    assert(h.percentile(1.0) == 0)
  }

  private def assertWithinError(ideal: Long, actual: Long): Unit = {
    val epsilon = Math.round(ideal * MetricsBucketedHistogram.DefaultErrorPercent)
    if (epsilon == 0) {
      val _ = assert(actual == ideal)
    } else {
      val _ = actual should be(ideal +- epsilon)
    }
  }

  test("percentile 1 to 100000") {
    val h = MetricsBucketedHistogram()
    val wantedPs = Array[Double](0.0, 0.5, 0.9, 0.99, 0.999, 0.9999, 1.0)

    def assertPercentiles(maxVal: Long): Unit = {
      val actuals = h.getQuantiles(wantedPs)
      actuals.zip(wantedPs).foreach {
        case (actual, wantedP) =>
          withClue(s"percentile=$wantedP") {
            // verify that each percentile is within the error bounds.
            val ideal = Math.round(wantedP * maxVal)
            assertWithinError(ideal, actual)

            // verify that getting each percentile 1-at-a-time
            // is the same as the bulk call
            assert(h.percentile(wantedP) == actual)
          }
      }
    }

    1L.to(100L).foreach(h.add)
    assertPercentiles(100)

    101L.to(1000L).foreach(h.add)
    assertPercentiles(1000)

    1001L.to(10000L).foreach(h.add)
    assertPercentiles(10000)

    10001L.to(100000L).foreach(h.add)
    assertPercentiles(100000)
  }

  test("percentile edge cases") {
    val max = MetricsBucketedHistogram()
    max.add(Long.MaxValue)
    assert(max.percentile(0.1) == 0)
    assert(max.percentile(1.0) == Int.MaxValue)

    val zero = MetricsBucketedHistogram()
    zero.add(0)
    assert(zero.percentile(0.0) == 0)
    assert(zero.percentile(0.1) == 0)
    assert(zero.percentile(1.0) == 0)
  }

  test("clear") {
    val h = MetricsBucketedHistogram()
    assert(h.percentile(0.0) == 0)
    assert(h.percentile(0.5) == 0)
    assert(h.percentile(1.0) == 0)

    h.add(100)
    assert(h.percentile(0.0) == 0)
    assert(h.percentile(0.5) == 100)
    assert(h.percentile(1.0) == 100)

    h.clear()
    assert(h.percentile(0.0) == 0)
    assert(h.percentile(0.5) == 0)
    assert(h.percentile(1.0) == 0)
  }

  test("sum") {
    val h = MetricsBucketedHistogram()
    assert(h.sum == 0)

    h.add(100)
    h.add(200)
    assert(h.sum == 300)

    h.clear()
    assert(h.sum == 0)
  }

  test("count") {
    val h = MetricsBucketedHistogram()
    assert(h.count == 0)

    h.add(100)
    h.add(200)
    assert(h.count == 2)

    h.clear()
    assert(h.count == 0)
  }

  test("average") {
    val h = MetricsBucketedHistogram()
    assert(h.average == 0.0)

    h.add(100)
    h.add(200)
    assert(h.average == 150.0)

    h.clear()
    assert(h.average == 0)
  }

  test("outliers are handled") {
    val h = MetricsBucketedHistogram()
    h.add(2137204091L)
    h.add(-1)
    h.add(Long.MinValue)
    h.add(Long.MaxValue)
  }

  test("exporting counts starts empty") {
    val h = MetricsBucketedHistogram()
    assert(h.bucketAndCounts == Seq.empty)
  }

  test("exporting counts tracks negative and extreme values") {
    val h = MetricsBucketedHistogram()
    h.add(-1)
    h.add(0)
    h.add(1)
    h.add(1)
    h.add(1)
    h.add(Int.MaxValue)
    h.add(Int.MaxValue)
    assert(h.bucketAndCounts == Seq(BucketAndCount(0, 1, 2), BucketAndCount(1, 2, 3),
      BucketAndCount(2137204091, Int.MaxValue, 2)))
  }

  test("exporting counts responds to clear") {
    val h = MetricsBucketedHistogram()
    h.add(-1)
    h.add(0)
    h.add(1)
    h.add(5)
    h.add(Int.MaxValue)
    h.clear()
    assert(h.bucketAndCounts == Seq.empty)
  }

  test("percentile and min and max stays within error bounds") {
    forAll(MetricsBucketedHistogramTest.generator) {
      case (samples: List[Int], p: Double) =>
        // although this uses Gen.nonEmptyContainerOf I observed an empty List
        // generated. As an example, this failed with an NPE:
        //
        //      Occurred when passed generated values (
        //        arg0 = (List(),0.941512699565841) // 4 shrinks
        //
        // Also, observed negative values even with Gen.chooseNum constraint:
        //
        //      Occurred when passed generated values (
        //        arg0 = (List(-1),0.9370612091967268) // 33 shrinks
        //
        whenever(samples.nonEmpty && samples.forall(_ >= 0)) {
          val h = MetricsBucketedHistogram()
          samples.foreach { s => h.add(s.toLong) }

          val sorted = samples.sorted.toIndexedSeq
          val index = (Math.round(sorted.size * p).toInt - 1).max(0)
          val ideal = sorted(index).toLong
          val actual = h.percentile(p)
          assertWithinError(ideal, actual)

          // check min and max too
          assertWithinError(sorted.head, h.minimum)
          assertWithinError(sorted.last, h.maximum)
        }
    }
  }

}

private object MetricsBucketedHistogramTest {

  def generator = for {
    samples <- Gen.nonEmptyContainerOf[List, Int](Gen.chooseNum(0, Int.MaxValue))
    percentile <- Gen.choose(0.5, 0.9999)
  } yield (samples, percentile)

}
