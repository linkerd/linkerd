package io.buoyant.namerd.iface.destination

import com.twitter.finagle._
import com.twitter.util.{Activity, Var}
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


  test("send new destination when it becomes available") {
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val dstSvc = new DestinationService(TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> new Namer {def lookup(path: Path) = Activity(addrStates) })))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    val vaddr = Var[Addr](Addr.Bound(Address("127.0.0.1", 7777)))

    addrStates() = Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, Path.read("/#/io.l5d.test/hello.svc.cluster.local"))))
    val result = await(dstSvc.get(dstReq).recv())
    assert(result.value == mkAddUpdate(Set(Address("127.0.0.1", 7777))))
  }

  test("send no endpoints update at start of stream"){
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val dstSvc = new DestinationService(TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> new Namer {def lookup(path: Path) = Activity(addrStates) })))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    val result = await(dstSvc.get(dstReq).recv())
    assert(
      result.value == Update(Some(OneofUpdate.NoEndpoints(NoEndpoints(Some(false)))))
    )
  }

  test("send no endpoints on stream start on failed name binding"){
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Failed(new Throwable("Failed binding")))
    val dstSvc = new DestinationService(TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> new Namer {def lookup(path: Path) = Activity(addrStates) })))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    val result = await(dstSvc.get(dstReq).recv())
    assert(
      result.value == Update(Some(OneofUpdate.NoEndpoints(NoEndpoints(Some(false)))))
    )
  }

  test("send remove update when endpoint is removed"){
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val dstSvc = new DestinationService(TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> new Namer {def lookup(path: Path) = Activity(addrStates) })))
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

  test("send no endpoints available when all endpoint addresses are removed"){
    val addrStates = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val dstSvc = new DestinationService(TestNameInterpreter(Seq(Path.read("/#/io.l5d.test") -> new Namer {def lookup(path: Path) = Activity(addrStates) })))
    val dstReq = GetDestination(Some("k8s"), Some("hello.svc.cluster.local"))
    val firstAddrSet = Var[Addr](Addr.Bound(Address("127.0.0.1", 7777), Address("127.0.0.1", 7778)))

    addrStates() = Activity.Ok(NameTree.Leaf(Name.Bound(firstAddrSet, Path.read("/#/io.l5d.test/hello.svc.cluster.local"))))
    val stream = dstSvc.get(dstReq)
    val firstRes = await(stream.recv())
    assert(firstRes.value == mkAddUpdate(Set(Address("127.0.0.1", 7777), Address("127.0.0.1", 7778))))
    firstAddrSet.update(Addr.Bound())
    val secondRes = await(stream.recv())
    assert(secondRes.value == mkRemoveUpdate(Set(Address("127.0.0.1", 7777), Address("127.0.0.1", 7778))))
    firstAddrSet.update(Addr.Bound())
    val thirdRes = await(stream.recv())
    assert(thirdRes.value == mkNoEndpointsUpdate(true))
  }
}
