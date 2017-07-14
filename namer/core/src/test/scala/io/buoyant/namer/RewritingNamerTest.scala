package io.buoyant.namer

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.finagle.buoyant.PathMatcher
import io.buoyant.test.FunSuite

class RewritingNamerTest extends FunSuite {

  def assertDelegates(from: String, to: String)(implicit namer: Namer) = {
    val NameTree.Leaf(Name.Path(result)) = namer.lookup(Path.read(from)).sample()
    assert(result == Path.read(to))
  }

  def assertDelegatesToNeg(from: String)(implicit namer: Namer) =
    assert(namer.lookup(Path.read(from)).sample() == NameTree.Neg)

  test("matches prefix") {
    implicit val namer = new RewritingNamer(PathMatcher("/one/two"), "/success")
    assertDelegates("/one/two/three", "/success")
  }

  test("matches wildcard") {
    implicit val namer = new RewritingNamer(PathMatcher("/one/*/three"), "/success")
    assertDelegates("/one/two/three", "/success")
  }

  test("mismatch") {
    implicit val namer = new RewritingNamer(PathMatcher("/one/*/four"), "/success")
    assertDelegatesToNeg("/one/two/three")
  }

  test("capture and rewrite") {
    implicit val namer = new RewritingNamer(PathMatcher("/{foo}/*/three"), "/success/{foo}")
    assertDelegates("/one/two/three", "/success/one")
  }

  test("failed capture") {
    implicit val namer = new RewritingNamer(PathMatcher("/{foo}/*/four"), "/success/{foo}")
    assertDelegatesToNeg("/one/two/three")
  }
}
