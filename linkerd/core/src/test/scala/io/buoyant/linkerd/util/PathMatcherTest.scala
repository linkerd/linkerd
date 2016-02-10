package io.buoyant.linkerd.util

import com.twitter.finagle.Path
import org.scalatest.FunSuite

class PathMatcherTest extends FunSuite {

  test("basic") {
    val matcher = PathMatcher("$3.${2}.buoyant.io")
    assert(matcher(Path.read("/us/fbi/xfiles/fox")) == Some("xfiles.fbi.buoyant.io"))
  }

  test("out of bounds") {
    val matcher = PathMatcher("$3.buoyant.io")
    assert(matcher(Path.read("/home/again")) == None)
  }

  test("bad replacement syntax") {
    intercept[IllegalArgumentException] { PathMatcher("$1.buoyant.io$") }
  }
}
