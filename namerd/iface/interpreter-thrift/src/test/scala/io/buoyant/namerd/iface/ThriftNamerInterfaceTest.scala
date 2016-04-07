package io.buoyant.namerd.iface

import com.twitter.conversions.time._
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.{Addr, Address, Dtab, Name, Namer, NameTree, Path}
import com.twitter.util.{Activity, Await, Var}
import io.buoyant.namerd.iface.{thriftscala => thrift}
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.FunSuite

class ThriftNamerInterfaceTest extends FunSuite {
  import ThriftNamerInterface._

  test("simple binding") {
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val interpreter = new NameInterpreter { def bind(dtab: Dtab, path: Path) = Activity(states) }
    val namer = new Namer { def lookup(path: Path) = Activity(states) }
    val namers = Map(Path.Utf8("atl") -> namer)

    val stampCounter = new AtomicLong(1)
    def stamper() = Stamp.mk(stampCounter.getAndIncrement)

    def retry() = 1.second

    val service = new ThriftNamerInterface(_ => interpreter, namers, stamper, retry)
    val clientId = TPath(Path.empty)

    // The first request before the tree has been refined -- no value initially
    val initName = thrift.NameRef(TStamp.empty, TPath("ysl", "thugger"), "testns")
    val initF = service.bind(thrift.BindReq(TDtab.empty, initName, clientId))
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
    val init = Await.result(initF)
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

  test("binding") {
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val interpreter = new NameInterpreter { def bind(dtab: Dtab, path: Path) = Activity(states) }
    val namer = new Namer { def lookup(path: Path) = Activity(states) }
    val namers = Map(Path.Utf8("atl") -> namer)

    val stampCounter = new AtomicLong(1)
    def stamper() = Stamp.mk(stampCounter.getAndIncrement)

    def retry() = 1.second

    val service = new ThriftNamerInterface(_ => interpreter, namers, stamper, retry)
    val clientId = TPath.empty

    val initName = thrift.NameRef(TStamp.empty, TPath("ysl", "thugger"), "testns")
    val initF = service.bind(thrift.BindReq(TDtab.empty, initName, clientId))
    assert(!initF.isDefined)

    val addrs = Var[Addr](Addr.Pending)
    val boundLeaf = NameTree.Leaf(Name.Bound(addrs, Path.Utf8("atl", "slime", "season"), Path.Utf8("3")))
    states() = Activity.Ok(boundLeaf)

    assert(initF.isDefined)
    val init = Await.result(initF)
    assert(init.tree.root == thrift.BoundNode.Leaf(thrift.BoundName(
      TPath("atl", "slime", "season"),
      TPath("3")
    )))
    assert(init.tree.nodes.isEmpty)

    val addrName = thrift.NameRef(TStamp.empty, TPath("atl", "slime", "season"), "testns")
    val addrF = service.addr(thrift.AddrReq(addrName, clientId))
    assert(!addrF.isDefined)

    val isa = new InetSocketAddress("8.8.8.8", 4949)
    addrs() = Addr.Bound(Address(isa))
    assert(addrF.isDefined)

    val boundAddr = {
      val ip = ByteBuffer.wrap(isa.getAddress.getAddress)
      val taddrs = Set(thrift.TransportAddress(ip, isa.getPort))
      thrift.Addr(TStamp.mk(2), thrift.AddrVal.Bound(thrift.BoundAddr(taddrs)))
    }
    assert(Await.result(addrF) == boundAddr)
  }
}
