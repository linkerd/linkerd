package io.buoyant.namerd.iface

import com.twitter.conversions.time._
import com.twitter.finagle.{Addr, Address, Dtab, Name, NameTree, Path}
import com.twitter.util._
import io.buoyant.namerd.iface.{thriftscala => thrift}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.ByteBuffer
import org.scalatest.FunSuite

class ThriftNamerClientTest extends FunSuite {
  import ThriftNamerInterface._

  class Rsp[T] {
    val promise = new Promise[T]
    var count = 1
  }

  class TestNamerService(clientId: Path) extends thrift.Namer.FutureIface {
    val bindingsMu = new {}
    var bindings = Map.empty[(String, Path, Dtab, TStamp), Rsp[thrift.Bound]]

    val addrsMu = new {}
    var addrs = Map.empty[(Path, TStamp), Rsp[thrift.Addr]]

    def bind(req: thrift.BindReq): Future[thrift.Bound] = {
      val thrift.BindReq(tdtab, thrift.NameRef(stamp, name, ns), cid) = req
      val dtab = Dtab.read(tdtab)
      assert(mkPath(cid) == clientId)
      bindingsMu.synchronized {
        val k = (ns, mkPath(name), dtab, stamp)
        bindings.get(k) match {
          case Some(rsp) =>
            rsp.count += 1
            rsp.promise
          case None =>
            val rsp = new Rsp[thrift.Bound]
            bindings += (k -> rsp)
            rsp.promise
        }
      }
    }

    def addr(req: thrift.AddrReq): Future[thrift.Addr] = {
      val thrift.AddrReq(thrift.NameRef(stamp, name, _), cid) = req
      assert(mkPath(cid) == clientId)
      addrsMu.synchronized {
        val k = (mkPath(name), stamp)
        addrs.get(k) match {
          case Some(rsp) =>
            rsp.count += 1
            rsp.promise
          case None =>
            val rsp = new Rsp[thrift.Addr]
            addrs += (k -> rsp)
            rsp.promise
        }
      }
    }
  }

  test("binds a logical name to a concrete address: a yeezy story") {
    val wolvesPath = Path.read("/yeezy/tlop/wolves")
    val illegalPath = Path.read("/illegal.dl/kanyewest/tlop/wolves")
    val tidalPath = Path.read("/listen.tidal.com/album/57273408")

    val namespace = "yeezy"
    val clientId = Path.read("/rando/x8u4i5j")
    val dtab = Dtab.read("""
      /yeezy/tlop/wolves => /listen.tidal.com/album/57273408;
      /yeezy/tlop => /illegal.dl/kanyewest/tlop;
    """)

    val service = new TestNamerService(clientId)
    val client = new ThriftNamerClient(service, namespace, clientId)
    @volatile var state: Activity.State[NameTree[Name.Bound]] = Activity.Pending
    val closer = client.bind(dtab, wolvesPath).states.respond(state = _)
    try {
      assert(state == Activity.Pending)

      val initPromise = service.bindingsMu.synchronized {
        val key = (namespace, wolvesPath, dtab, TStamp.empty)
        assert(service.bindings.contains(key))
        val rsp = service.bindings(key)
        assert(rsp.count == 1)
        assert(!service.bindings.contains((namespace, wolvesPath, dtab, TStamp.mk(1))))
        rsp.promise
      }

      assert(state == Activity.Pending)
      initPromise.setValue(thrift.Bound(
        TStamp.mk(1),
        thrift.BoundTree(
          thrift.BoundNode.Alt(Seq(123, 124)),
          Map(
            123 -> thrift.BoundNode.Leaf(thrift.BoundName(
              TPath("illegal.dl", "kanyewest", "tlop"),
              TPath("wolves")
            )),
            124 -> thrift.BoundNode.Leaf(thrift.BoundName(TPath(tidalPath), TPath.empty))
          )
        ),
        "testns"
      ))

      state match {
        case Activity.Ok(tree) => tree match {
          case NameTree.Alt(NameTree.Leaf(illegal), NameTree.Leaf(tidal)) =>
            assert(illegal.id == Path.read("/illegal.dl/kanyewest/tlop"))
            assert(illegal.path == Path.Utf8("wolves"))
            assert(tidal.id == tidalPath)
            assert(tidal.path.isEmpty)

            val updatePromise = service.bindingsMu.synchronized {
              val key = (namespace, wolvesPath, dtab, TStamp.mk(1))
              assert(service.bindings.contains(key))
              val rsp = service.bindings(key)
              assert(rsp.count == 1)
              rsp.promise
            }

            var addr: Addr = Addr.Pending
            val addrCloser = tidal.addr.changes.respond(addr = _)
            assert(addr == Addr.Pending)

            val tidalIps = Set(
              InetAddress.getByAddress(Array(54.toByte, 236.toByte, 221.toByte, 177.toByte)),
              InetAddress.getByAddress(Array(54.toByte, 209.toByte, 157.toByte, 226.toByte)),
              InetAddress.getByAddress(Array(54.toByte, 172.toByte, 179.toByte, 46.toByte)),
              InetAddress.getByAddress(Array(54.toByte, 209.toByte, 160.toByte, 26.toByte))
            )
            val addrPromise = service.addrsMu.synchronized {
              val key = (tidalPath, TStamp.empty)
              assert(service.addrs.contains(key))
              val rsp = service.addrs(key)
              assert(rsp.count == 1)
              assert(addr == Addr.Pending)
              assert(!service.addrs.contains((tidalPath, TStamp.mk(2))))
              rsp.promise
            }
            val ips = tidalIps.map { ip =>
              thrift.TransportAddress(ByteBuffer.wrap(ip.getAddress), 80)
            }
            addrPromise.setValue(thrift.Addr(
              TStamp.mk(2),
              thrift.AddrVal.Bound(thrift.BoundAddr(ips))
            ))
            val addrs = tidalIps.map { ip =>
              Address(new InetSocketAddress(ip, 80))
            }
            assert(addr == Addr.Bound(addrs.toSet))

            service.addrsMu.synchronized {
              val key = (tidalPath, TStamp.mk(2))
              assert(service.addrs.contains(key))
              val rsp = service.addrs(key)
              assert(rsp.count == 1)
            }

          case tree => fail(s"$tree is not an Alt(Leaf, Leaf)")
        }
        case state => fail(s"$state is not Ok")
      }
    } finally Await.result(closer.close(), 10.seconds)

  }
}
