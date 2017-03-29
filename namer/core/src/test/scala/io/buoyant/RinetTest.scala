package io.buoyant

import com.twitter.finagle._
import com.twitter.util.Future
import io.buoyant.namer.RichActivity
import io.buoyant.test.FunSuite

class RinetTest extends FunSuite {

  test("rinet binds") {
    val path = Path.read("/$/io.buoyant.rinet/12345/localhost/residual")
    val tree = await(Namer.global.bind(NameTree.Leaf(path)).toFuture)
    val NameTree.Leaf(bound) = tree
    assert(bound.id == Path.read("/$/io.buoyant.rinet/12345/localhost"))
    assert(bound.path == Path.read("/residual"))
    val addr = await(bound.addr.changes.filter(_ != Addr.Pending).toFuture())
    val Addr.Bound(addresses, _) = addr
    assert(addresses == Set(Address("localhost", 12345)))
  }
}
