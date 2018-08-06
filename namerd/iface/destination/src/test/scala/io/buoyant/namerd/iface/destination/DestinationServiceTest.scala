package io.buoyant.namerd.iface.destination

import com.twitter.finagle._
import com.twitter.util.{Activity, Throw, Var}
import io.buoyant.grpc.runtime.Stream
import io.buoyant.namer.ConfiguredDtabNamer
import io.buoyant.test.FunSuite
import io.linkerd.proxy.destination.Update.OneofUpdate

class DestinationServiceTest extends FunSuite {
  import DestinationService._
  import io.linkerd.proxy.destination._
  private[this] def TestNameInterpreter(namers: Seq[(Path, Namer)]) = ConfiguredDtabNamer(
    Activity.value(Dtab.read("/svc=>/#/io.l5d.test")),
    namers
  )

  private[this] def mkNamer(vAddr: Var[Activity.State[NameTree[Name.Bound]]]) = {
    new Namer {
      def lookup(path: Path) = Activity(vAddr)
    }
  }


  test("new endpoints when it becomes available") {
    val vaddr = Var[Activity.State[NameTree[Name.Bound]]](Activity.Ok(NameTree.Leaf(Name.Bound(Var.value(Addr.Bound(Address("127.0.0.1", 7777))), "path"))))
    val dstSvc = new DestinationService(
      "svc",
      TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> mkNamer(vaddr))))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    vaddr() = Activity.Ok(NameTree.Leaf(Name.Bound(Var.value(Addr.Bound(Address("127.0.0.1", 7777))), "path")))//NameTree.Leaf(Name.Bound(vaddr, Path.read("/#/io.l5d.test/hello.svc.cluster.local"))))

    val stream = dstSvc.get(dstReq)
    val Stream.Releasable(addUpdate, addRelease) = await(stream.recv())
    assert(addUpdate == mkAddUpdate(Set(Address("127.0.0.1", 7777))))
    addRelease()

  }

  test("no endpoints {false} on failed activity"){
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Failed(new Throwable("error")))
    val dstSvc = new DestinationService(
      "svc",
      TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> mkNamer(addrStates))))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    val result = await(dstSvc.get(dstReq).recv())
    assert(
      result.value == Update(Some(OneofUpdate.NoEndpoints(NoEndpoints(Some(false)))))
    )
  }

  test("remove update when endpoint is removed"){
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val dstSvc = new DestinationService(
      "svc",
      TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> mkNamer(addrStates))))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    val firstAddrSet = Var[Addr](Addr.Bound(Address("127.0.0.1", 7777), Address("127.0.0.1", 7778)))

    addrStates() = Activity.Ok(NameTree.Leaf(Name.Bound(firstAddrSet, Path.read("/#/io.l5d.test/hello.svc.cluster.local"))))
    val stream = dstSvc.get(dstReq)
    val firstRes = await(stream.recv())
    assert(firstRes.value == mkAddUpdate(Set(Address("127.0.0.1", 7777), Address("127.0.0.1", 7778))))
    firstAddrSet.update(Addr.Bound(Address("127.0.0.1", 7778)))
    val secondRes = await(stream.recv())
    assert(secondRes.value == mkRemoveUpdate(Set(Address("127.0.0.1", 7777))))
  }
}
