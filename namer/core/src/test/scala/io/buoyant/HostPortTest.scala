package io.buoyant

import com.twitter.finagle._
import io.buoyant.test.FunSuite

class HostPortTest extends FunSuite {

  def lookup(path: Path) =
    await(Namer.global.lookup(path).values.toFuture()).get

  test("io.buoyant.hostportPfx matches IP:PORT") {
    val path = Path.read("/$/io.buoyant.hostportPfx/ia/127.1:8080/etc")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("ia", "127.1", "8080", "etc"))))
  }

  test("io.buoyant.hostportPfx matches HOST:PORT") {
    val path = Path.read("/$/io.buoyant.hostportPfx/ia/google.com:8180/etc")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("ia", "google.com", "8180", "etc"))))
  }

  test("io.buoyant.hostportPfx does not match HOST:PORT:ETC") {
    val path = Path.read("/$/io.buoyant.hostportPfx/ia/google.com:8180:abcdef/etc")
    assert(lookup(path) == NameTree.Neg)
  }

  test("io.buoyant.porthostPfx matches IP:PORT") {
    val path = Path.read("/$/io.buoyant.porthostPfx/ia/127.1:8080/etc")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("ia", "8080", "127.1", "etc"))))
  }
}
