package io.buoyant.namer.k8s

import com.twitter.finagle.Name.Bound
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Address, _}
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.namer.Metadata
import io.buoyant.test.FunSuite
import java.net.InetSocketAddress

class K8sLocalnodeNamerTest extends FunSuite {

  test("filters to localnode") {

    val ni = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Bound]] = Activity.value(
        NameTree.Leaf(Name.Bound(Var(Addr.Bound(
          Address.Inet(new InetSocketAddress("1.1.1.2", 8888), Map(Metadata.nodeName -> "nodeA")),
          Address.Inet(new InetSocketAddress("1.1.2.2", 8888), Map(Metadata.nodeName -> "nodeB")),
          Address.Inet(new InetSocketAddress("1.1.3.2", 8888), Map(Metadata.nodeName -> "nodeC"))
        )), Path.read("/#/io.l5d.k8s/default/http/foo")))
      )
    }

    NameInterpreter.setGlobal(ni)

    val namer = new K8sLocalnodeNamer(Path.read("/#/io.l5d.k8s.localnode"), "nodeA")

    val act = namer.bind(NameTree.Leaf(Path.read("/#/io.l5d.k8s/default/http/foo")))
    val tree = await(act.values.toFuture.flatMap(Future.const))

    assert(tree == NameTree.Leaf(Name.Bound(Var(Addr.Bound(
      Address.Inet(new InetSocketAddress("1.1.1.2", 8888), Map(Metadata.nodeName -> "nodeA"))
    )), Path.read("/#/io.l5d.k8s.localnode/#/io.l5d.k8s/default/http/foo"))))
  }
}
