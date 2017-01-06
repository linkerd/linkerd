package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HeaderPathIdentifierTest extends FunSuite with Awaits {

  test("identifies requests by header, without segment limit") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier(Headers.Path, None, Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two", Stream.empty())

    Dtab.local = localDtab
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/pfx/one/two"))
        assert(base == baseDtab)
        assert(local == localDtab)
      case id => fail(s"unexpected identification: $id")
    }
  }

  test("identifies requests by header, with segment limit") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier(Headers.Path, Some(1), Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two", Stream.empty())

    Dtab.local = localDtab
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/pfx/one"))
        assert(base == baseDtab)
        assert(local == localDtab)
      case id => fail(s"unexpected identification: $id")
    }
  }

  test("ignores url params") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier(Headers.Path, None, Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two/?three=four", Stream.empty())

    Dtab.local = localDtab
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/pfx/one/two"))
        assert(base == baseDtab)
        assert(local == localDtab)
      case id => fail(s"unexpected identification: $id")
    }
  }

  test("does not identify requests by header, with path shorter than segment limit") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier(Headers.Path, Some(3), Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two", Stream.empty())

    Dtab.local = localDtab
    assert(await(identifier(req0)).isInstanceOf[UnidentifiedRequest[Request]])
  }

  test("identifies requests with arbitrary header") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier("lolz", None, Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two", Stream.empty())
    req0.headers.set("lolz", "/rofl/hah")

    Dtab.local = localDtab
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/pfx/rofl/hah"))
        assert(base == baseDtab)
        assert(local == localDtab)
      case id => fail(s"unexpected identification: $id")
    }
  }

  test("does not identify requests by header if header missing") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier("lolz", None, Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two", Stream.empty())

    Dtab.local = localDtab
    assert(await(identifier(req0)).isInstanceOf[UnidentifiedRequest[Request]])
  }

  test("does not identify requests by header if header missing and segments required") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderPathIdentifier("lolz", Some(1), Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/one/two", Stream.empty())

    Dtab.local = localDtab
    assert(await(identifier(req0)).isInstanceOf[UnidentifiedRequest[Request]])
  }
}
