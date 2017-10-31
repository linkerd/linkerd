package com.medallia.l5d.namer

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import io.buoyant.test.FunSuite

class ReplacingNamerTest extends FunSuite {

  def assertDelegates(from: String, to: String)(implicit namer: Namer) = {
    val NameTree.Leaf(Name.Path(result)) = namer.lookup(Path.read(from)).sample()
    assert(result == Path.read(to))
  }

  test("matches regex") {
    implicit val namer = new ReplacingNamer("th.ee".r, "success")
    assertDelegates("/one/two/three", "/one/two/success")
  }

  test("doesn't match regex") {
    implicit val namer = new ReplacingNamer("at.*ee".r, "success")
    assertDelegates("/one/two/three", "/one/two/three")
  }

  test("only matches last element") {
    implicit val namer = new ReplacingNamer("elem".r, "success")
    assertDelegates("/elem/elem/elem", "/elem/elem/success")
  }

}
