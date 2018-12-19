package io.buoyant.namerd.iface

import com.twitter.conversions.DurationOps._
import com.twitter.finagle._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.util._
import io.buoyant.namer.{DelegateTree, Delegator, Metadata}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import io.buoyant.test.Awaits
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.FunSuite

class ThriftNamerInterfaceTest extends FunSuite with Awaits {
  import ThriftNamerInterface._

  val clientId = TPath(Path.empty)
  val ns = "testns"

  test("bind") {
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    def interpreter(ns: String) = new NameInterpreter {
      def bind(dtab: Dtab, path: Path) = Activity(states)
    }
    val stampCounter = new AtomicLong(1)
    val stamper = () => Stamp.mk(stampCounter.getAndIncrement)
    val service = new ThriftNamerInterface(interpreter, Map.empty, stamper, Capacity.default, NullStatsReceiver)

    // The first request before the tree has been refined -- no value initially
    val initName = thrift.NameRef(TStamp.empty, TPath("ysl", "thugger"), ns)
    val initF = service.bind(thrift.BindReq("", initName, clientId))
    assert(!initF.isDefined)

    val ss2Addr, imupAddr, ss3Addr = Var[Addr](Addr.Pending)
    states() = Activity.Ok(NameTree.Alt(
      NameTree.Leaf(Name.Bound(ss2Addr, Path.Utf8("slime", "season", "2"))),
      NameTree.Union(
        NameTree.Weighted(2.0, NameTree.Leaf(Name.Bound(imupAddr, Path.Utf8("atl", "im", "up")))),
        NameTree.Weighted(0.2, NameTree.Leaf(Name.Bound(ss3Addr, Path.Utf8("atl", "slime", "season", "3"))))
      )
    ))

    assert(initF.isDefined)
    val init = Await.result(initF, 1.second)
    assert(init.tree.root.isInstanceOf[thrift.BoundNode.Alt])
    init.tree.root match {
      case thrift.BoundNode.Alt(Seq(id0, id1)) =>
        assert(init.tree.nodes.contains(id0) && init.tree.nodes.contains(id1))
        assert(init.tree.nodes(id0) ==
          thrift.BoundNode.Leaf(thrift.BoundName(TPath(Path.Utf8("slime", "season", "2")))))
        init.tree.nodes(id1) match {
          case thrift.BoundNode.Weighted(Seq(w0, w1)) =>
            assert(init.tree.nodes.contains(w0.id) && init.tree.nodes.contains(w1.id))
            assert(w0.weight == 2.0 && w1.weight == 0.2)
            assert(init.tree.nodes(w0.id) ==
              thrift.BoundNode.Leaf(thrift.BoundName(TPath("atl", "im", "up"))))
            assert(init.tree.nodes(w1.id) ==
              thrift.BoundNode.Leaf(thrift.BoundName(TPath("atl", "slime", "season", "3"))))

          case node => fail(s"$node is not a BoundNode.Weighted(w0, w1)")
        }

      case node => fail(s"$node is not a BoundNode.Alt(id0, id1)")
    }
  }

  test("delegate") {
    val delegation = new Promise[DelegateTree[Name.Bound]]
    def interpreter(ns: String) = new NameInterpreter with Delegator {
      override def delegate(dtab: Dtab, tree: NameTree[Name.Path]) = delegation
      override def bind(dtab: Dtab, path: Path) = ???
      override def dtab: Activity[Dtab] = ???
    }

    val stampCounter = new AtomicLong(1)
    val stamper = () => Stamp.mk(stampCounter.getAndIncrement)
    val service = new ThriftNamerInterface(interpreter, Map.empty, stamper, Capacity.default, NullStatsReceiver)

    // The first request before the tree has been refined -- no value initially
    val path = TPath("ysl", "thugger")
    val initTree = thrift.Delegation(
      TStamp.empty,
      thrift.DelegateTree(
        thrift.DelegateNode(path, Dentry.nop.show, thrift.DelegateContents.PathLeaf(path)),
        Map.empty
      ),
      ns
    )
    val initF = service.delegate(thrift.DelegateReq("", initTree, clientId))
    assert(!initF.isDefined)

    delegation.setValue(DelegateTree.Delegate(Path.read("/ysl/thugger"), Dentry.read("/ysl => /atl"),
      DelegateTree.Leaf(Path.read("/ysl/thugger"), Dentry.read("/ysl => /atl"), Name.Bound(null, Path.read("/atl/thugger"), Path.empty))))

    val init = await(initF)
    val delegate = init.tree.root.contents.asInstanceOf[thrift.DelegateContents.Delegate]
    val leaf = init.tree.nodes(delegate.delegate).contents.asInstanceOf[thrift.DelegateContents.BoundLeaf]
    assert(leaf.boundLeaf.id == TPath("atl", "thugger"))
    assert(leaf.boundLeaf.residual == TPath())
  }

  test("dtab") {
    val states = Var[Activity.State[Dtab]](Activity.Pending)
    def interpreter(ns: String) = new NameInterpreter with Delegator {
      override def delegate(dtab: Dtab, tree: NameTree[Name.Path]) = ???
      override def bind(dtab: Dtab, path: Path) = ???
      override def dtab: Activity[Dtab] = Activity(states)
    }

    val stampCounter = new AtomicLong(1)
    val stamper = () => Stamp.mk(stampCounter.getAndIncrement)
    val service = new ThriftNamerInterface(interpreter, Map.empty, stamper, Capacity.default, NullStatsReceiver)

    val initF = service.dtab(thrift.DtabReq(TStamp.empty, "pandoracorn", clientId))
    assert(!initF.isDefined)

    val dtab = Dtab.read("/foo => /bar")
    states() = Activity.Ok(dtab)

    val init = await(initF)
    assert(Dtab.read(init.dtab) == dtab)
  }

  trait AddrCtx {
    def interpreter(ns: String): NameInterpreter = ???
    val pfx = Path.Utf8("#", "atl")
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val namers = Map(pfx -> new Namer { def lookup(path: Path) = Activity(states) })
    val stampCounter = new AtomicLong(1)
    val stamper = () => Stamp.mk(stampCounter.getAndIncrement)
    val service = new ThriftNamerInterface(interpreter, namers, stamper, Capacity.default, NullStatsReceiver)
  }

  test("addr") {
    val _ = new AddrCtx {
      val initRef = thrift.NameRef(TStamp.empty, TPath("#", "atl", "slime", "season"), ns)
      val initF = service.addr(thrift.AddrReq(initRef, clientId))
      assert(!initF.isDefined)

      val addrs = Var[Addr](Addr.Pending)
      val leaf = NameTree.Leaf(Name.Bound(addrs, Path.Utf8("#", "atl", "slime", "season")))
      states() = Activity.Ok(leaf)
      assert(!initF.isDefined) // addrs still pending

      val isa = new InetSocketAddress("8.8.8.8", 4949)
      val isaMeta = Addr.Metadata(Metadata.authority -> "acme.co", "ignored" -> "value")
      val addresses: Set[Address] = Set(Address.Inet(isa, isaMeta))
      val addressesMeta = Addr.Metadata(Metadata.authority -> "example.com", "another" -> "value")
      addrs() = Addr.Bound(addresses, addressesMeta)
      assert(initF.isDefined)
      val init = Await.result(initF, 1.second)

      val boundAddr = {
        val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
        val ipMeta = thrift.AddrMeta(Some("acme.co"))
        val taddrs = Set(thrift.TransportAddress(ip, isa.getPort, Some(ipMeta)))
        val baddrMeta = thrift.AddrMeta(Some("example.com"))
        val baddr = thrift.BoundAddr(taddrs, Some(baddrMeta))
        thrift.Addr(TStamp.mk(1), thrift.AddrVal.Bound(baddr))
      }
      assert(Await.result(initF, 1.second) == boundAddr)
    }
  }

  test("addr: deleted and re-created") {
    val _ = new AddrCtx {
      val id = TPath("#", "atl", "slime", "season")
      val rsp0 = service.addr(thrift.AddrReq(thrift.NameRef(TStamp.empty, id, ns), clientId))
      assert(!rsp0.isDefined)

      val addrs0 = Var[Addr](Addr.Pending)
      val leaf = NameTree.Leaf(Name.Bound(addrs0, ThriftNamerInterface.mkPath(id)))
      states() = Activity.Ok(leaf)
      assert(!rsp0.isDefined) // addrs still pending

      val isa = new InetSocketAddress("8.8.8.8", 4949)
      val isaMeta = Addr.Metadata(Metadata.authority -> "acme.co", "ignored" -> "value")
      val addresses: Set[Address] = Set(Address.Inet(isa, isaMeta))
      val addressesMeta = Addr.Metadata(Metadata.authority -> "example.com", "another" -> "value")
      addrs0() = Addr.Bound(addresses, addressesMeta)
      assert(rsp0.isDefined)

      val boundAddr0 = {
        val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
        val ipMeta = thrift.AddrMeta(Some("acme.co"))
        val taddrs = Set(thrift.TransportAddress(ip, isa.getPort, Some(ipMeta)))
        val baddrMeta = thrift.AddrMeta(Some("example.com"))
        val baddr = thrift.BoundAddr(taddrs, Some(baddrMeta))
        thrift.Addr(TStamp.mk(1), thrift.AddrVal.Bound(baddr))
      }
      assert(Await.result(rsp0, 1.second) == boundAddr0)

      val ref1 = thrift.NameRef(TStamp.mk(1), id, ns)
      val rsp1 = service.addr(thrift.AddrReq(ref1, clientId))
      assert(!rsp1.isDefined)

      addrs0() = Addr.Neg
      states() = Activity.Ok(NameTree.Neg)
      assert(rsp1.isDefined)
      assert(Await.result(rsp1, 1.second) ==
        thrift.Addr(TStamp.mk(2), thrift.AddrVal.Neg(thrift.Void())))

      val rsp2 = service.addr(thrift.AddrReq(thrift.NameRef(TStamp.mk(2), id, ns), clientId))
      assert(!rsp2.isDefined)

      val addrs1 = Var[Addr](Addr.Pending)
      states() = Activity.Ok(NameTree.Leaf(Name.Bound(addrs1, ThriftNamerInterface.mkPath(id))))
      assert(!rsp2.isDefined)
      addrs1() = Addr.Bound(addresses, addressesMeta)
      assert(rsp2.isDefined)

      val boundAddr1 = {
        val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
        val ipMeta = thrift.AddrMeta(Some("acme.co"))
        val taddrs = Set(thrift.TransportAddress(ip, isa.getPort, Some(ipMeta)))
        val baddrMeta = thrift.AddrMeta(Some("example.com"))
        val baddr = thrift.BoundAddr(taddrs, Some(baddrMeta))
        thrift.Addr(TStamp.mk(3), thrift.AddrVal.Bound(baddr))
      }
      assert(Await.result(rsp2, 1.second) == boundAddr1)
    }
  }

  test("addr strips transformer prefixes") {
    val _ = new AddrCtx {
      val initRef = thrift.NameRef(TStamp.empty, TPath("%", "optimus", "#", "atl", "slime", "seaon"), ns)
      val initF = service.addr(thrift.AddrReq(initRef, clientId))
      assert(!initF.isDefined)

      val addrs = Var[Addr](Addr.Pending)
      val leaf = NameTree.Leaf(Name.Bound(addrs, Path.Utf8("%", "optimus", "#", "alt", "slime", "season")))
      states() = Activity.Ok(leaf)
      assert(!initF.isDefined) // addrs still pending

      val isa = new InetSocketAddress("8.8.8.8", 4949)
      val isaMeta = Addr.Metadata(Metadata.authority -> "acme.co", "ignored" -> "value")
      val addresses: Set[Address] = Set(Address.Inet(isa, isaMeta))
      val addressesMeta = Addr.Metadata(Metadata.authority -> "example.com", "another" -> "value")
      addrs() = Addr.Bound(addresses, addressesMeta)
      assert(initF.isDefined)
      val init = Await.result(initF, 1.second)

      val boundAddr = {
        val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
        val ipMeta = thrift.AddrMeta(Some("acme.co"))
        val taddrs = Set(thrift.TransportAddress(ip, isa.getPort, Some(ipMeta)))
        val baddrMeta = thrift.AddrMeta(Some("example.com"))
        val baddr = thrift.BoundAddr(taddrs, Some(baddrMeta))
        thrift.Addr(TStamp.mk(1), thrift.AddrVal.Bound(baddr))
      }
      assert(Await.result(initF, 1.second) == boundAddr)
    }
  }

}
