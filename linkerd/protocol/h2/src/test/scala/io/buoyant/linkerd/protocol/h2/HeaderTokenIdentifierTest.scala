package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2._
import io.buoyant.router.RoutingFactory._
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class HeaderTokenIdentifierTest extends FunSuite with Awaits {

  test("identifies requests by header") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderTokenIdentifier(":authority", Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/path", Stream.empty())

    Dtab.local = localDtab
    await(identifier(req0)) match {
      case IdentifiedRequest(Dst.Path(name, base, local), req1) =>
        assert(name == Path.read("/pfx/wacky"))
        assert(base == baseDtab)
        assert(local == localDtab)
      case id => fail(s"unexpected identification: $id")
    }
  }

  test("does not identify requests by header if header missing") {
    val baseDtab = Dtab.read("/pfx => /other")
    val localDtab = Dtab.read("/pfx => /another")
    val identifier = new HeaderTokenIdentifier("lolz", Path.Utf8("pfx"), () => baseDtab)
    val req0 = Request("http", Method.Get, "wacky", "/path", Stream.empty())

    Dtab.local = localDtab
    assert(await(identifier(req0)).isInstanceOf[UnidentifiedRequest[Request]])
  }
}
