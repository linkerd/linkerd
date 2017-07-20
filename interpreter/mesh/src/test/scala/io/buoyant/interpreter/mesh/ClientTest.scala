package io.buoyant.interpreter.mesh

import com.twitter.finagle.{Addr, Address, Dtab, Name, NameTree, Path}
import com.twitter.finagle.util.DefaultTimer
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Promise}
import io.buoyant.grpc.runtime.{GrpcStatus, Stream}
import io.buoyant.test.FunSuite
import io.linkerd.mesh
import java.net.{InetAddress, InetSocketAddress}

class ClientTest extends FunSuite {

  val unusedDelegator = new mesh.Delegator {
    def getDtab(req: mesh.DtabReq) = Future.exception(GrpcStatus.Unimplemented())
    def streamDtab(req: mesh.DtabReq) = Stream.exception(GrpcStatus.Unimplemented())
    def getDelegateTree(req: mesh.DelegateTreeReq) = Future.exception(GrpcStatus.Unimplemented())
    def streamDelegateTree(req: mesh.DelegateTreeReq) = Stream.exception(GrpcStatus.Unimplemented())
  }

  test("bind") {
    val bindReqP = new Promise[mesh.BindReq]
    val bindRsps = Stream.mk[mesh.BoundTreeRsp]
    val addrReqP = new Promise[mesh.ReplicasReq]
    val addrRsps = Stream.mk[mesh.Replicas]
    val client = {
      val interp = new mesh.Interpreter {
        def getBoundTree(req: mesh.BindReq) = Future.exception(GrpcStatus.Unimplemented())
        def streamBoundTree(req: mesh.BindReq) = {
          bindReqP.setValue(req)
          bindRsps
        }
      }
      val resolv = new mesh.Resolver {
        def getReplicas(req: mesh.ReplicasReq) = Future.exception(GrpcStatus.Unimplemented())
        def streamReplicas(req: mesh.ReplicasReq) = {
          addrReqP.setValue(req)
          addrRsps
        }
      }
      Client(Path.Utf8("foons"), interp, resolv, unusedDelegator, scala.Stream.empty, DefaultTimer)
    }

    val act = client.bind(Dtab.read("/stuff => /mas"), Path.read("/some/name"))

    @volatile var state: Option[Activity.State[NameTree[Name.Bound]]] = None
    val responding = act.states.respond(s => state = Some(s))
    try {
      eventually { assert(state == Some(Activity.Pending)) }
      val bindReq = await(bindReqP)
      val send0F = {
        val leaf0 = mesh.BoundNameTree(
          node = Some(mesh.BoundNameTree.OneofNode.Leaf(mesh.BoundNameTree.Leaf(
            id = Some(mesh.Path(Seq(Buf.Utf8("id0")))),
            residual = Some(mesh.Path(Seq(Buf.Utf8("extra0"))))
          )))
        )
        val leaf1 = mesh.BoundNameTree(
          node = Some(mesh.BoundNameTree.OneofNode.Leaf(mesh.BoundNameTree.Leaf(
            id = Some(mesh.Path(Seq(Buf.Utf8("id1"))))
          )))
        )
        val rsp = mesh.BoundTreeRsp(
          tree = Some(mesh.BoundNameTree(
            node = Some(mesh.BoundNameTree.OneofNode.Alt(mesh.BoundNameTree.Alt(Seq(leaf0, leaf1))))
          ))
        )
        bindRsps.send(rsp)
      }

      eventually {
        state match {
          case Some(Activity.Ok(NameTree.Alt(NameTree.Leaf(leaf0), NameTree.Leaf(leaf1)))) =>
            assert(leaf0.id == Path.Utf8("id0"))
            assert(leaf0.path == Path.Utf8("extra0"))
            assert(leaf1.id == Path.Utf8("id1"))
          case state =>
            fail(s"unexpected state $state")
        }
      }
      assert(send0F.isDefined == false) // not released yet

      val send1F = {
        val rsp = mesh.BoundTreeRsp(
          tree = Some(mesh.BoundNameTree(
            node = Some(mesh.BoundNameTree.OneofNode.Leaf(mesh.BoundNameTree.Leaf(
              id = Some(mesh.Path(Seq(Buf.Utf8("id2")))),
              residual = Some(mesh.Path(Seq(Buf.Utf8("extra2"))))
            )))
          ))
        )
        bindRsps.send(rsp)
      }

      await(send0F) // released
      state match {
        case Some(Activity.Ok(NameTree.Leaf(leaf2))) =>
          assert(leaf2.id == Path.Utf8("id2"))
          assert(leaf2.path == Path.Utf8("extra2"))

          // now check address observation
          @volatile var addr: Option[Addr] = None
          val observing = leaf2.addr.changes.respond(a => addr = Some(a))
          try {
            eventually { assert(addr == Some(Addr.Pending)) }
            val addrReq = await(addrReqP)
            assert(addrReq == mesh.ReplicasReq(Some(mesh.Path(Seq(Buf.Utf8("id2"))))))

            val ip0 = InetAddress.getByName("192.168.42.66")
            val meta0: Map[String, Any] = Map("nodeName" -> "anode")

            val ip1 = InetAddress.getByName("fe80::62f8:1dff:fed0:4452")
            val meta1 = Map.empty[String, Any]

            val endpoints = Seq(
              mesh.Endpoint(
                Some(mesh.Endpoint.AddressFamily.INET4),
                Some(Buf.ByteArray.Owned(ip0.getAddress)),
                Some(1234),
                Some(mesh.Endpoint.Meta(nodeName = Some("anode")))
              ),
              mesh.Endpoint(
                Some(mesh.Endpoint.AddressFamily.INET6),
                Some(Buf.ByteArray.Owned(ip1.getAddress)),
                Some(5678),
                Some(mesh.Endpoint.Meta())
              )
            )
            val send2F = addrRsps.send(mesh.Replicas(Some(
              mesh.Replicas.OneofResult.Bound(mesh.Replicas.Bound(endpoints))
            )))
            assert(send2F.isDefined == false) // not released yet

            eventually {
              addr match {
                case Some(Addr.Bound(addrs, meta)) =>
                  assert(addrs.size == 2)
                  assert(addrs contains Address.Inet(new InetSocketAddress(ip0, 1234), meta0))
                  assert(addrs contains Address.Inet(new InetSocketAddress(ip1, 5678), meta1))

                case a => fail(s"unexpected addr: $addr")
              }
            }

          } finally await(observing.close())

        case state =>
          fail(s"unexpected state $state")
      }
      assert(send1F.isDefined == false) // not released yet

    } finally await(responding.close())
  }
}
