package io.buoyant

import com.twitter.finagle._
import io.buoyant.namer.RichActivity
import io.buoyant.test.FunSuite

class HostPortTest extends FunSuite {

  def lookup(path: Path) =
    await(Namer.global.lookup(path).toFuture)

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

  test("io.buoyant.hostportPfx matches HOST:PORT with string port") {
    val path = Path.read("/$/io.buoyant.hostportPfx/ia/google.com:http/etc")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("ia", "google.com", "http", "etc"))))
  }

  test("io.buoyant.hostportPfx matches IP:PORT with string port") {
    val path = Path.read("/$/io.buoyant.hostportPfx/ia/127.1:http/etc")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("ia", "127.1", "http", "etc"))))
  }

  test("io.buoyant.hostportPfx does not match HOST:PORT:ETC with string port") {
    val path = Path.read("/$/io.buoyant.hostportPfx/ia/google.com:http:abcdef/etc")
    assert(lookup(path) == NameTree.Neg)
  }

  test("io.buoyant.porthostPfx matches IP:PORT with string port") {
    val path = Path.read("/$/io.buoyant.porthostPfx/ia/127.1:http/etc")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("ia", "http", "127.1", "etc"))))
  }
}
