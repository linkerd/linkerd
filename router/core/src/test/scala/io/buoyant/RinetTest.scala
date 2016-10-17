package io.buoyant

import com.twitter.finagle._
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class RinetTest extends FunSuite with Awaits {

  test("rinet binds") {
    val path = Path.read("/$/io.buoyant.rinet/12345/localhost")
    val tree = await(Namer.global.lookup(path).values.toFuture.flatMap(Future.const))
    val NameTree.Leaf(Name.Bound(va)) = tree
    val addr = await(va.changes.filter(_ != Addr.Pending).toFuture())
    val Addr.Bound(addresses, _) = addr
    assert(addresses == Set(Address("localhost", 12345)))
  }
}
