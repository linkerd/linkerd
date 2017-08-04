package io.buoyant.router

import com.twitter.finagle.Stack
import io.buoyant.router.h2.{LocalClassifierStreamStatsFilter, PerDstPathStreamStatsFilter}
import io.buoyant.test.FunSuite

class H2Test extends FunSuite {

  def get[T](stk: Stack[T], role: Stack.Role): Option[Stack[T]] = {
    var found: Option[Stack[T]] = None
    stk.foreach { s =>
      if (s.head.role == role) found = Some(s)
    }
    found
  }

  test("client stack contains classified stream filter") {
    val statsModule = get(H2.router.client.stack, LocalClassifierStreamStatsFilter.role).get
    assert(statsModule.head.description == LocalClassifierStreamStatsFilter.description)

    val perDstStats = get(H2.router.client.stack, PerDstPathStreamStatsFilter.module.role).get
    assert(perDstStats.head.description == "perdstpathstatsfilter, using H2 stream classification")
  }
}
