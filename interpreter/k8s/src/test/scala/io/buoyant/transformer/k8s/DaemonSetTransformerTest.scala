package io.buoyant.transformer.k8s

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.namer.{Metadata, RichActivity}
import io.buoyant.test.FunSuite
import io.buoyant.transformer.{MetadataGatewayTransformer, Netmask, SubnetGatewayTransformer}
import java.net.InetSocketAddress

class DaemonSetTransformerTest extends FunSuite {

  test("daemonset transformer uses daemonset") {
    val daemonset = Activity.value(
      NameTree.Leaf(Name.Bound(Var(Addr.Bound(
        Address("1.1.1.1", 4141),
        Address("1.1.2.1", 4141),
        Address("1.1.3.1", 4141)
      )), Path.read("/#/io.l5d.k8s/default/incoming/l5d"), Path.empty))
    )

    val transformer = new SubnetGatewayTransformer(Path.empty, daemonset, Netmask("255.255.255.0"))

    val interpreter = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Bound]] = Activity.value(
        NameTree.Leaf(Name.Bound(Var(Addr.Bound(
          Address(Path.Utf8.unapplySeq(path).get.head, 8888)
        )), path, Path.empty))
      )
    }

    val transformed = transformer.wrap(interpreter)

    for {
      (dest, expected) <- Seq(
        "/1.1.1.55" -> "1.1.1.1",
        "/1.1.2.55" -> "1.1.2.1",
        "/1.1.3.55" -> "1.1.3.1"
      )
    } {
      val bounds = await(
        transformed.bind(Dtab.empty, Path.read(dest)).toFuture
      ).eval.get

      val addrs = bounds.flatMap { bound =>
        val Addr.Bound(addresses, _) = bound.addr.sample
        addresses
      }

      assert(addrs == Set(Address(expected, 4141)))
    }
  }

  test("daemonset transformer with hostNetwork") {
    val daemonset = Activity.value(
      NameTree.Leaf(Name.Bound(Var(Addr.Bound(
        Address.Inet(new InetSocketAddress("7.7.7.1", 4141), Map(Metadata.nodeName -> "7.7.7.1")),
        Address.Inet(new InetSocketAddress("7.7.7.2", 4141), Map(Metadata.nodeName -> "7.7.7.2")),
        Address.Inet(new InetSocketAddress("7.7.7.3", 4141), Map(Metadata.nodeName -> "7.7.7.3"))
      )), Path.read("/#/io.l5d.k8s/default/incoming/l5d"), Path.empty))
    )

    val transformer = new MetadataGatewayTransformer(Path.empty, daemonset, Metadata.nodeName)

    val interpreter = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Bound]] = {
        val Path.Utf8(ip, nodeName) = path
        Activity.value(
          NameTree.Leaf(Name.Bound(Var(Addr.Bound(
            Address.Inet(new InetSocketAddress(ip, 8888), Map(Metadata.nodeName -> nodeName))
          )), path, Path.empty))
        )
      }
    }

    val transformed = transformer.wrap(interpreter)

    for {
      (dest, expected) <- Seq(
        "/1.1.1.55/7.7.7.1" -> "7.7.7.1",
        "/1.1.1.55/7.7.7.2" -> "7.7.7.2",
        "/1.1.1.55/7.7.7.3" -> "7.7.7.3"
      )
    } {
      val bounds = await(
        transformed.bind(Dtab.empty, Path.read(dest)).toFuture
      ).eval.get

      val addrs = bounds.flatMap { bound =>
        val Addr.Bound(addresses, _) = bound.addr.sample
        addresses
      }

      assert(addrs == Set(Address.Inet(new InetSocketAddress(expected, 4141), Map(Metadata.nodeName -> expected))))
    }
  }

  test("returns Addr.Neg when no daemonsets available") {
    val daemonset = Activity.value(
      NameTree.Leaf(Name.Bound(Var(Addr.Bound(
        Address("1.1.1.1", 4141),
        Address("1.1.2.1", 4141),
        Address("1.1.3.1", 4141)
      )), Path.read("/#/io.l5d.k8s/default/incoming/l5d"), Path.empty))
    )

    val transformer = new SubnetGatewayTransformer(Path.empty, daemonset, Netmask("255.255.255.0"))

    val interpreter = new NameInterpreter {
      override def bind(
        dtab: Dtab,
        path: Path
      ): Activity[NameTree[Bound]] = Activity.value(
        NameTree.Leaf(Name.Bound(Var(Addr.Bound(
          Address(Path.Utf8.unapplySeq(path).get.head, 8888)
        )), path, Path.empty))
      )
    }

    val transformed = transformer.wrap(interpreter)

    val bounds = await(
      transformed.bind(Dtab.empty, Path.read("/1.1.4.55")).toFuture
    ).eval.get

    val addrs = for (bound <- bounds) {
      assert(bound.addr.sample == Addr.Neg)
    }
  }
}
