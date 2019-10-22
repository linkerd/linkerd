package io.buoyant.transformer.k8s

import com.twitter.finagle.Name.Bound
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.namer.{Metadata, RichActivity}
import io.buoyant.test.FunSuite
import io.buoyant.transformer.{MetadataGatewayTransformer, Netmask, SubnetGatewayTransformer, GatewayTransformer}
import io.buoyant.namer.{DelegatingNameTreeTransformer, Delegator, Paths, TransformerConfig, WithNameTreeTransformer}
import java.net.InetSocketAddress
import io.buoyant.config.Parser
import io.buoyant.config.types.Port
import com.twitter.finagle.{Dtab}
import org.scalatest.Inside

class DaemonSetTransformerTest extends FunSuite
  with Inside {

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

  private[this] def parse(yaml: String): DaemonSetTransformerConfig =
    Parser.objectMapper(yaml, Iterable(Seq(new DaemonSetTransformerInitializer)))
      .readValue[TransformerConfig](yaml)
      .asInstanceOf[DaemonSetTransformerConfig]

  val yaml_hostNetwork_false =
    s"""|kind: io.l5d.k8s.daemonset
        |k8sHost: "foo"
        |k8sPort: 8888
        |namespace: "test-namespace"
        |service: "test-service"
        |port: test-port
        |hostNetwork: false
        |""".stripMargin

  val yaml_hostNetwork_true =
    s"""|kind: io.l5d.k8s.daemonset
        |k8sHost: "foo"
        |k8sPort: 9999
        |namespace: "test-namespace2"
        |service: "test-service2"
        |port: test-port2
        |hostNetwork: true
        |""".stripMargin

  test("daemonset transformer has admin handler with expected url path") {
    val config = parse(yaml_hostNetwork_false)
    inside(config) {
      case DaemonSetTransformerConfig(k8sHost, k8sPort, namespace, service, port, hostNetwork) =>
        assert(k8sHost.contains("foo"))
        assert(k8sPort.contains(Port(8888)))
        assert(namespace.contains("test-namespace"))
        assert(service.contains("test-service"))
        assert(port.contains("test-port"))
        assert(hostNetwork.contains(false))
    }

    val transfomer: GatewayTransformer = config.mk(Stack.Params.empty).asInstanceOf[GatewayTransformer]
    transfomer.adminHandlers.headOption match {
      case Some(h) =>
        assert(h.url == "/namer_state/io.l5d.k8s.daemonset/test-namespace/test-port/test-service.json")
      case None =>
        fail("DaemonSetTransformer does not have a watch state handler configured")
    }
  }

  test("when wrapping a interpreter, the new transformed interpreter implements trait WithNameTreeTransformer and is able to stack transformers") {
    val transformer = parse(yaml_hostNetwork_false).mk(Stack.Params.empty)

    val interpreter = new NameInterpreter {
      override def bind(dtab: Dtab, path: Path): Activity[NameTree[Bound]] = {
        val Path.Utf8(ip, nodeName) = path
        Activity.value(
          NameTree.Leaf(Name.Bound(Var(Addr.Bound(
            Address.Inet(new InetSocketAddress(ip, 8888), Map(Metadata.nodeName -> nodeName))
          )), path, Path.empty))
        )
      }
    }

    val transformed = transformer.wrap(interpreter)
    transformed match {
      case withNameTreeTransformer: WithNameTreeTransformer =>
        assert(withNameTreeTransformer.transformers == Seq(transformer))
      case _ =>
          fail("new namer does not have trait WithNameTreeTransformer")
      }

    val transformer2 = parse(yaml_hostNetwork_true).mk(Stack.Params.empty)
    val transformed2 = transformer2.wrap(transformed)
    transformed2 match {
      case withNameTreeTransformer: WithNameTreeTransformer =>
        assert(withNameTreeTransformer.transformers == Seq(transformer, transformer2))
      case _ =>
        fail("new namer does not have trait WithNameTreeTransformer")
    }
  }

  test("when wrapping a delegating interpreter, the new transformed interpreter implements trait WithNameTreeTransformer and is able to stack transformers") {
    val transformer = parse(yaml_hostNetwork_false).mk(Stack.Params.empty).asInstanceOf[DelegatingNameTreeTransformer]

    val states = Var[Activity.State[Dtab]](Activity.Pending)
    val interpreter = new NameInterpreter with Delegator {
      override def delegate(dtab: Dtab, tree: NameTree[Name.Path]) = ???
      //override def bind(dtab: Dtab, path: Path) = ???
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

      override def dtab: Activity[Dtab] = Activity(states)
    }

    val transformed = transformer.delegatingWrap(interpreter)
    transformed match {
      case withNameTreeTransformer: WithNameTreeTransformer =>
        assert(withNameTreeTransformer.transformers == Seq(transformer))
      case _ =>
        fail("new namer does not have trait WithNameTreeTransformer")
    }

    val transformer2 = parse(yaml_hostNetwork_true).mk(Stack.Params.empty).asInstanceOf[DelegatingNameTreeTransformer]
    val transformed2 = transformer2.delegatingWrap(transformed)
    transformed2 match {
      case withNameTreeTransformer: WithNameTreeTransformer =>
        assert(withNameTreeTransformer.transformers == Seq(transformer, transformer2))
      case _ =>
        fail("new namer does not have trait WithNameTreeTransformer")
    }
  }

  test("when wrapping a namer, the new transformed namer implements trait WithNameTreeTransformer and is able to stack transformers") {
    val transformer = parse(yaml_hostNetwork_false).mk(Stack.Params.empty)

    val prefix = Paths.ConfiguredNamerPrefix ++ Path.read("/test-namer")
    val namer = new Namer {
      def lookup(path: Path) = Activity.exception(TestNamingError(prefix ++ path))
    }

    val transformed = transformer.wrap(namer)
    transformed match {
      case withNameTreeTransformer: WithNameTreeTransformer =>
        assert(withNameTreeTransformer.transformers == Seq(transformer))
      case _ =>
        fail("new namer does not have trait WithNameTreeTransformer")
    }

    val transformer2 = parse(yaml_hostNetwork_true).mk(Stack.Params.empty)
    val transformed2 = transformer2.wrap(transformed)
    transformed2 match {
      case withNameTreeTransformer: WithNameTreeTransformer =>
        assert(withNameTreeTransformer.transformers == Seq(transformer, transformer2))
      case _ =>
        fail("new namer does not have trait WithNameTreeTransformer")
    }
  }

  case class TestNamingError(path: Path) extends Throwable(s"error naming ${path.show}") {
    override def toString = s"TestNamingError(${path.show})"
  }

  //Test extracting handlers from namer multiple transformers

  //Test extracting handlers from interpreter multiple transformers

  //Test watch state from endpoint
}
