package io.buoyant.http

import com.twitter.finagle.{Status => SvcStatus, _}
import com.twitter.finagle.http.{Http => _, _}
import com.twitter.util._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class NamerTest extends FunSuite with Awaits {

  def lookup(path: Path) =
    await(Namer.global.lookup(path).values.toFuture()).get

  test("anyMethod") {
    val path = Path.read("/$/io.buoyant.http.anyMethod/GET/foo/bar/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))))
  }

  test("anyMethod: not a method") {
    val path = Path.read("/$/io.buoyant.http.anyMethod/A.B/foo/bar/bah")
    assert(lookup(path) == NameTree.Neg)
  }

  test("anyMethodPfx") {
    val path = Path.read("/$/io.buoyant.http.anyMethodPfx/foo/GET/bar/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))))
  }

  test("anyMethodPfx: not a method") {
    val path = Path.read("/$/io.buoyant.http.anyMethodPfx/foo/A.B/bar/bah")
    assert(lookup(path) == NameTree.Neg)
  }

  test("anyHost") {
    val path = Path.read("/$/io.buoyant.http.anyHost/a.b/foo/bar/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))))
  }

  test("anyHost: not a host") {
    val path = Path.read("/$/io.buoyant.http.anyHost/a%b/foo/bar/bah")
    assert(lookup(path) == NameTree.Neg)
  }

  test("anyHostPfx") {
    val path = Path.read("/$/io.buoyant.http.anyHostPfx/foo/a.b/bar/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))))
  }

  test("anyHostPfx: not a host") {
    val path = Path.read("/$/io.buoyant.http.anyHostPfx/foo/a%b/bar/bah")
    assert(lookup(path) == NameTree.Neg)
  }

  test("subdomainOf") {
    val path = Path.read("/$/io.buoyant.http.subdomainOf/a.b/foo.a.b/bar/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))))
  }

  test("subdomainOfPfx") {
    val path = Path.read("/$/io.buoyant.http.subdomainOfPfx/a.b/foo/bar.a.b/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))))
  }

  test("domainToPath") {
    val path = Path.read("/$/io.buoyant.http.domainToPath/foo.buoyant.io")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("io", "buoyant", "foo"))))
  }

  test("domainToPathPfx") {
    val path = Path.read("/$/io.buoyant.http.domainToPathPfx/pfx/foo.buoyant.io")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("pfx", "io", "buoyant", "foo"))))
  }

  test("status") {
    val client = Http.newService("/$/io.buoyant.http.status/401/foo/bar.a.b/bah")
    val rsp = await(client(Request()))
    assert(rsp.status == Status.Unauthorized)
  }

  test("status: invalid") {
    val path = Path.read("/$/io.buoyant.http.status/foo/bar.a.b/bah")
    assert(lookup(path) == NameTree.Neg)
  }

  test("status: no code") {
    val path = Path.read("/$/io.buoyant.http.status")
    assert(lookup(path) == NameTree.Neg)
  }

}
