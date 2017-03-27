package io.buoyant

import com.twitter.finagle._
import io.buoyant.namer.RichActivity
import io.buoyant.test.FunSuite

class HttpTest extends FunSuite {

  def lookup(path: Path) =
    await(Namer.global.lookup(path).toFuture)

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

  test("subdomainOfPfx with underscore") {
    val path = Path.read("/$/io.buoyant.http.subdomainOfPfx/a.b/foo/bar_suffix.a.b/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar_suffix", "bah"))))
  }

  test("subdomainOfPfx with dash") {
    val path = Path.read("/$/io.buoyant.http.subdomainOfPfx/a.b/foo/bar-suffix.a.b/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar-suffix", "bah"))))
  }

  test("subdomainOfPfx with port") {
    val path = Path.read("/$/io.buoyant.http.subdomainOfPfx/a.b/foo/bar.a.b:9090/bah")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("foo", "bar", "bah"))), s"${lookup(path)}")
  }

  test("domainToPath") {
    val path = Path.read("/$/io.buoyant.http.domainToPath/foo.buoyant.io")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("io", "buoyant", "foo"))))
  }

  test("domainToPathPfx") {
    val path = Path.read("/$/io.buoyant.http.domainToPathPfx/pfx/foo.buoyant.io")
    assert(lookup(path) == NameTree.Leaf(Name.Path(Path.Utf8("pfx", "io", "buoyant", "foo"))))
  }

}
