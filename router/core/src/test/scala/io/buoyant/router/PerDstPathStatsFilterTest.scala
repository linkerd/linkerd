package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Service}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.util.{Future, Local}
import io.buoyant.test.FunSuite

class PerDstPathStatsFilterTest extends FunSuite {

  def mkFilter[Req, Rsp](sr: StatsReceiver) = {
    sr.counter("creates").incr()
    Filter.mk[Req, Rsp, Req, Rsp] { (req, svc) =>
      sr.counter("calls").incr()
      svc(req)
    }
  }

  def mkService(stats: StatsReceiver) = {
    val filter = new PerDstPathStatsFilter[Unit, Unit](stats, mkFilter _)
    filter.andThen(Service.const(Future.Unit))
  }

  def setContext(f: String => Path) =
    Filter.mk[String, Unit, Unit, Unit] { (req, service) =>
      val save = Local.save()
      try Contexts.local.let(ctx.DstPath, Dst.Path(f(req))) { service(()) }
      finally Local.restore(save)
    }

  test("scopes stats with dst/path") {
    val stats = new InMemoryStatsReceiver
    val service = setContext(Path.Utf8("req", _)).andThen(mkService(stats))
    await(service("cat").join(service("dog")).join(service("dog")))
    assert(stats.counters == Map(
      Seq("dst/path", "req/dog", "creates") -> 1,
      Seq("dst/path", "req/dog", "calls") -> 2,
      Seq("dst/path", "req/cat", "creates") -> 1,
      Seq("dst/path", "req/cat", "calls") -> 1
    ))
  }

  test("adds no stats when Dst.Path is empty") {
    val stats = new InMemoryStatsReceiver
    val service = setContext(_ => Path.empty).andThen(mkService(stats))
    await(service("dog").join(service("cat")))
    assert(stats.counters.isEmpty)
  }

  test("adds no stats when Dst.Path not in context") {
    val stats = new InMemoryStatsReceiver
    val service = mkService(stats)
    await(service(()))
    assert(stats.counters.isEmpty)
  }
}
