package io.buoyant.k8s

import com.twitter.concurrent.AsyncStream
import io.buoyant.test.FunSuite
import org.scalatest.prop.GeneratorDrivenPropertyChecks


class WatchableTest extends FunSuite
  with GeneratorDrivenPropertyChecks {
  test("items in AsyncStream.increasingOnly always greater than prior items") {

    import Watchable._

    forAll { (ns: Seq[Int]) =>
      val as = AsyncStream.fromSeq(ns)
      var max = Int.MinValue
      as.increasingOnly
        .foreach { i =>
          assert(i >= max)
          max = i
        }
    }
  }

}
