package io.buoyant.transformer.k8s

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.namer.{Metadata, MetadataFiltertingNameTreeTransformer, RichActivity}
import io.buoyant.test.FunSuite
import io.buoyant.transformer.{Netmask, SubnetLocalTransformer}
import java.net.{InetAddress, InetSocketAddress}

class LocalnodeTransformerTest extends FunSuite {

  test("localnode transformer selects local instance") {
    val transformer = new SubnetLocalTransformer(Path.empty, Seq(InetAddress.getByName("1.1.1.1")), Netmask("255.255.255.0"))

    val interpreter = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Bound]] = Activity.value(
        NameTree.Leaf(Name.Bound(Var(Addr.Bound(
          Address("1.1.1.2", 8888),
          Address("1.1.1.3", 8888),
          Address("1.1.2.2", 8888),
          Address("1.1.2.3", 8888)
        )), path, Path.empty))
      )
    }

    val transformed = transformer.wrap(interpreter)

    val bounds = await(
      transformed.bind(Dtab.empty, Path.empty).toFuture
    ).eval.get

    val addrs = bounds.flatMap { bound =>
      val Addr.Bound(addresses, _) = bound.addr.sample
      addresses
    }

    assert(addrs == Set(Address("1.1.1.2", 8888), Address("1.1.1.3", 8888)))
  }

  test("localnode transformer with host network") {
    val transformer = new MetadataFiltertingNameTreeTransformer(Path.empty, Metadata.nodeName, "7.7.7.7")

    val interpreter = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Bound]] = Activity.value(
        NameTree.Leaf(Name.Bound(Var(Addr.Bound(
          Address.Inet(new InetSocketAddress("1.1.1.1", 8888), Map(Metadata.nodeName -> "7.7.7.7")),
          Address.Inet(new InetSocketAddress("1.1.1.2", 8888), Map(Metadata.nodeName -> "7.7.7.7")),
          Address.Inet(new InetSocketAddress("1.1.1.3", 8888), Map(Metadata.nodeName -> "7.7.7.8")),
          Address.Inet(new InetSocketAddress("1.1.1.4", 8888), Map(Metadata.nodeName -> "7.7.7.8"))
        )), path, Path.empty))
      )
    }

    val transformed = transformer.wrap(interpreter)

    val bounds = await(
      transformed.bind(Dtab.empty, Path.empty).toFuture
    ).eval.get

    val addrs = bounds.flatMap { bound =>
      val Addr.Bound(addresses, _) = bound.addr.sample
      addresses
    }

    assert(addrs == Set(
      Address.Inet(new InetSocketAddress("1.1.1.1", 8888), Map(Metadata.nodeName -> "7.7.7.7")),
      Address.Inet(new InetSocketAddress("1.1.1.2", 8888), Map(Metadata.nodeName -> "7.7.7.7"))
    ))
  }
}
