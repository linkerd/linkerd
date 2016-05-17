package io.buoyant.namer.serversets

import com.twitter.finagle._
import com.twitter.util.Var
import io.buoyant.namer.NamerTestUtil
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class ServersetNamerTest extends FunSuite with NamerTestUtil {
  val prefix = Path.read("/#/some/prefix")

  test("falls back to path prefixes") {
    namer("/foo/bar").lookup(Path.read("/foo/bar/x/y/z")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix ++ Path.read("/foo/bar"))
        assert(name.path == Path.read("/x/y/z"))
      case _ => fail("failed to bind")
    }
  }

  test("neg") {
    assert(
      namer("/this").lookup(Path.read("/that")).sample() == NameTree.Neg
    )
  }

  test("exact match") {
    namer("/foo/bar").lookup(Path.read("/foo/bar")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix ++ Path.read("/foo/bar"))
        assert(name.path == Path.empty)
      case _ => fail("failed to bind")
    }
  }

  test("empty path") {
    namer("/").lookup(Path.read("/x/y/z")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix)
        assert(name.path == Path.read("/x/y/z"))
      case _ => fail("failed to bind")
    }
  }

  test("id is bound name") {
    val testNamer = namer("/test")
    assertBoundIdAutobinds(testNamer, prefix ++ Path.read("/test"), prefix)
  }

  def namer(path: String) = new ServersetNamer("host", prefix) {
    val loopback = Address(new InetSocketAddress(InetAddress.getLoopbackAddress, 1))
    /** Resolve a resolver string to a Var[Addr]. */
    override protected[this] def resolve(spec: String): Var[Addr] =
      if (spec == s"zk2!host!$path") Var.value(Addr.Bound(loopback))
      else Var.value(Addr.Neg)
  }
}
