package io.buoyant.namer.serversets

import com.twitter.finagle._
import com.twitter.finagle.serverset2.BouyantZkResolver
import com.twitter.util.{Activity, Var}
import io.buoyant.namer.NamerTestUtil
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

class ServersetNamerTest extends FunSuite with NamerTestUtil {
  val prefix = Path.read("/#/some/prefix")
  val loopback = Address(new InetSocketAddress(InetAddress.getLoopbackAddress, 1))
  val pathAddr = Var[Addr](Addr.Pending)
  val otherAddr = Var[Addr](Addr.Pending)

  test("falls back to path prefixes") {
    pathAddr() = Addr.Bound(loopback)
    otherAddr() = Addr.Neg
    namer("/foo/bar").lookup(Path.read("/foo/bar/x/y/z")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix ++ Path.read("/foo/bar"))
        assert(name.path == Path.read("/x/y/z"))
      case _ => fail("failed to bind")
    }
  }

  test("neg") {
    otherAddr() = Addr.Neg
    assert(
      namer("/this").lookup(Path.read("/that")).sample() == NameTree.Neg
    )
  }

  test("exact match") {
    pathAddr() = Addr.Bound(loopback)
    namer("/foo/bar").lookup(Path.read("/foo/bar")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix ++ Path.read("/foo/bar"))
        assert(name.path == Path.empty)
      case _ => fail("failed to bind")
    }
  }

  test("empty path") {
    pathAddr() = Addr.Bound(loopback)
    otherAddr() = Addr.Neg
    namer("/").lookup(Path.read("/x/y/z")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix)
        assert(name.path == Path.read("/x/y/z"))
      case _ => fail("failed to bind")
    }
  }

  test("id is bound name") {
    pathAddr() = Addr.Bound(loopback)
    val testNamer = namer("/test")
    assertBoundIdAutobinds(testNamer, prefix ++ Path.read("/test"), prefix)
  }

  test("handles pending") {
    otherAddr() = Addr.Pending
    val act = namer("/foo/bar").lookup(Path.read("/foo/bar/x/y/z"))
    assert(act.run.sample() == Activity.Pending)
    otherAddr() = Addr.Neg
    pathAddr() = Addr.Bound(loopback)
    act.sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix ++ Path.read("/foo/bar"))
        assert(name.path == Path.read("/x/y/z"))
      case x => fail("failed to bind")
    }
  }

  test("shard lookup") {
    pathAddr() = Addr.Bound(loopback)
    otherAddr() = Addr.Neg
    namer("/foo/bar").lookup(Path.read("/foo/bar:http#0")).sample() match {
      case NameTree.Leaf(name: Name.Bound) =>
        assert(name.id == prefix ++ Path.read("/foo/bar:http#0"))
        assert(name.path == Path.empty)
      case _ => fail("failed to bind")
    }
  }

  def namer(path: String) =
      new ServersetNamer(Path.Utf8("host"), prefix, new BouyantZkResolver {
    protected[this] def addrOf(
        zkHosts: String,
        zkPath: String,
        endpoint: Option[String],
        shardId: Option[Int]): Var[Addr] =
      if ((zkHosts == "host") && (zkPath == path)) pathAddr
      else otherAddr
  })
}
