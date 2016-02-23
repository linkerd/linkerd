package io.l5d.experimental

import org.scalatest.FunSuite

class MarathonTest extends FunSuite {

  test("sanity") {
    // ensure it doesn't totally blowup
    MarathonConfig(None, None, None).newNamer()
  }
}
