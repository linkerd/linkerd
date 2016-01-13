package io.buoyant.linkerd

import org.scalatest.FunSuite

class BuildTest extends FunSuite {

  test("load") {
    assert(Build.load().version != null)
  }
}
