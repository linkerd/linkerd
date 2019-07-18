package io.buoyant.namerd.iface.mesh

import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle._
import com.twitter.io.Buf
import com.twitter.util.{Activity, Var}
import io.buoyant.test.FunSuite
import io.linkerd.mesh.Endpoint.AddressFamily
import io.linkerd.mesh.{Endpoint, Replicas, ReplicasReq, Path => MPath}

class ResolverServiceTest extends FunSuite {

  test("getReplicas") {
    val pfx = Path.read("/#/atl")
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val namers = Map(pfx -> new Namer { def lookup(path: Path) = Activity(states) })
    val resolver = new ResolverService.ServerImpl(namers, NullStatsReceiver)

    val vaddr = Var[Addr](Addr.Bound(Address("1.2.3.4", 7777)))
    states() = Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, Path.read("/#/atl/slime/season"))))

    val path = Path.read("/#/atl/slime/season")
    val req = ReplicasReq(Some(MPath(path.elems)))

    val endpoint = Endpoint(
      inetAf = Some(AddressFamily.INET4),
      address = Some(Buf.ByteArray.Owned(Array[Byte](1, 2, 3, 4))),
      port = Some(7777),
      meta = Map.empty
    )
    val rsp = await(resolver.getReplicas(req))
    assert(rsp.result.get == Replicas.OneofResult.Bound(Replicas.Bound(Seq(endpoint))))
  }

  test("streamReplicas") {
    val pfx = Path.read("/#/atl")
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val namers = Map(pfx -> new Namer { def lookup(path: Path) = Activity(states) })
    val resolver = new ResolverService.ServerImpl(namers, NullStatsReceiver)

    val path = Path.read("/#/atl/slime/season")
    val req = ReplicasReq(Some(MPath(path.elems)))

    val vaddr = Var[Addr](Addr.Bound(Address("1.2.3.4", 7777)))
    states() = Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, Path.read("/#/atl/slime/season"))))
    val endpoint0 = Endpoint(
      inetAf = Some(AddressFamily.INET4),
      address = Some(Buf.ByteArray.Owned(Array[Byte](1, 2, 3, 4))),
      port = Some(7777),
      meta = Map.empty
    )

    val stream = resolver.streamReplicas(req)

    val item0 = await(resolver.streamReplicas(req).recv())
    assert(item0.value.result.get == Replicas.OneofResult.Bound(Replicas.Bound(Seq(endpoint0))))
    item0.release()

    vaddr() = Addr.Bound(Address("5.6.7.8", 7777))
    val endpoint1 = Endpoint(
      inetAf = Some(AddressFamily.INET4),
      address = Some(Buf.ByteArray.Owned(Array[Byte](5, 6, 7, 8))),
      port = Some(7777),
      meta = Map.empty
    )
    val item1 = await(resolver.streamReplicas(req).recv())
    assert(item1.value.result.get == Replicas.OneofResult.Bound(Replicas.Bound(Seq(endpoint1))))
    item1.release()
  }

  test("getReplicas strips transformer prefixes") {
    val pfx = Path.read("/#/atl")
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val namers = Map(pfx -> new Namer { def lookup(path: Path) = Activity(states) })
    val resolver = new ResolverService.ServerImpl(namers, NullStatsReceiver)

    val vaddr = Var[Addr](Addr.Bound(Address("1.2.3.4", 7777)))
    states() = Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, Path.read("/%/optimus/#/atl/slime/season"))))

    val path = Path.read("/%/optimus/#/atl/slime/season")
    val req = ReplicasReq(Some(MPath(path.elems)))

    val endpoint = Endpoint(
      inetAf = Some(AddressFamily.INET4),
      address = Some(Buf.ByteArray.Owned(Array[Byte](1, 2, 3, 4))),
      port = Some(7777),
      meta = Map.empty
    )
    val rsp = await(resolver.getReplicas(req))
    assert(rsp.result.get == Replicas.OneofResult.Bound(Replicas.Bound(Seq(endpoint))))
  }

  test("streamReplicas strips transformer prefixes") {
    val pfx = Path.read("/#/atl")
    val states = Var[Activity.State[NameTree[Name.Bound]]](Activity.Pending)
    val namers = Map(pfx -> new Namer { def lookup(path: Path) = Activity(states) })
    val resolver = new ResolverService.ServerImpl(namers, NullStatsReceiver)

    val vaddr = Var[Addr](Addr.Bound(Address("1.2.3.4", 7777)))
    states() = Activity.Ok(NameTree.Leaf(Name.Bound(vaddr, Path.read("/%/optimus/#/atl/slime/season"))))

    val path = Path.read("/%/optimus/#/atl/slime/season")
    val req = ReplicasReq(Some(MPath(path.elems)))

    val endpoint0 = Endpoint(
      inetAf = Some(AddressFamily.INET4),
      address = Some(Buf.ByteArray.Owned(Array[Byte](1, 2, 3, 4))),
      port = Some(7777),
      meta = Map.empty
    )

    val stream = resolver.streamReplicas(req)

    val item0 = await(resolver.streamReplicas(req).recv())
    assert(item0.value.result.get == Replicas.OneofResult.Bound(Replicas.Bound(Seq(endpoint0))))
    item0.release()

    vaddr() = Addr.Bound(Address("5.6.7.8", 7777))
    val endpoint1 = Endpoint(
      inetAf = Some(AddressFamily.INET4),
      address = Some(Buf.ByteArray.Owned(Array[Byte](5, 6, 7, 8))),
      port = Some(7777),
      meta = Map.empty
    )
    val item1 = await(resolver.streamReplicas(req).recv())
    assert(item1.value.result.get == Replicas.OneofResult.Bound(Replicas.Bound(Seq(endpoint1))))
    item1.release()
  }
}
