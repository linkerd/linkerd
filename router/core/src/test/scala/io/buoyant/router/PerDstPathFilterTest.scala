package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Service}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.util.{Future, Local}
import io.buoyant.router.context.DstPathCtx
import io.buoyant.test.FunSuite

class PerDstPathFilterTest extends FunSuite {

  def mkFilterer[Req, Rsp](statsReceiver: StatsReceiver) = { path: Path =>
    val sr = statsReceiver.scope(path.show)
    sr.counter("creates").incr()
    Filter.mk[Req, Rsp, Req, Rsp] { (req, svc) =>
      sr.counter("calls").incr()
      svc(req)
    }
  }

  def mkService(sr: StatsReceiver) = {
    val filter = new PerDstPathFilter[Unit, Unit](mkFilterer(sr))
    filter.andThen(Service.const(Future.Unit))
  }

  def setContext(f: String => Path) =
    Filter.mk[String, Unit, Unit, Unit] { (req, service) =>
      val save = Local.save()
      try Contexts.local.let(DstPathCtx, Dst.Path(f(req))) { service(()) }
      finally Local.restore(save)
    }

  test("applies cached per-Dst.Path-filter") {
    val stats = new InMemoryStatsReceiver
    val service = setContext(Path.Utf8("req", _)).andThen(mkService(stats.scope("pfx")))
    await(service("cat").join(service("dog")).join(service("dog")))
    assert(stats.counters == Map(
      Seq("pfx", "/req/dog", "creates") -> 1,
      Seq("pfx", "/req/dog", "calls") -> 2,
      Seq("pfx", "/req/cat", "creates") -> 1,
      Seq("pfx", "/req/cat", "calls") -> 1
    ))
  }

  test("adds no stats when Dst.Path is empty") {
    val stats = new InMemoryStatsReceiver
    val service = setContext(_ => Path.empty).andThen(mkService(stats.scope("pfx")))
    await(service("dog").join(service("cat")))
    assert(stats.counters.isEmpty)
  }

  test("adds no stats when Dst.Path not in context") {
    val stats = new InMemoryStatsReceiver
    val service = mkService(stats.scope("pfx"))
    await(service(()))
    assert(stats.counters.isEmpty)
  }

}
