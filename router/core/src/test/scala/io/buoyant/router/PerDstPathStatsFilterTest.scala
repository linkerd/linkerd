package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Service}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.util.{Future, Local}
import io.buoyant.test.FunSuite

class PerDstPathStatsFilterTest extends FunSuite {

  def mkFilter(sr: StatsReceiver) = {
    Filter.mk[Unit, Unit, Unit, Unit] { (_, svc) =>
      sr.counter("before").incr()
      val rspF = svc(())
      rspF.ensure(sr.counter("after").incr())
      rspF
    }
  }

  test("scopes stats with dst/path") {
    val stats = new InMemoryStatsReceiver
    val path = Path.Utf8("dog")
    val setContext = Filter.mk[Unit, Unit, Unit, Unit] { (req, service) =>
      val save = Local.save()
      try Contexts.local.let(ctx.DstPath, Dst.Path(path)) { service(req) }
      finally Local.restore(save)
    }
    val filter = new PerDstPathStatsFilter[Unit, Unit](stats, mkFilter _)
    val service = setContext.andThen(filter).andThen(Service.const(Future.Unit))
    await(service(()))
    assert(stats.counters == Map(
      Seq("dst/path", "dog", "before") -> 1,
      Seq("dst/path", "dog", "after") -> 1
    ))
  }

  test("adds no stats when Dst.Path not in context") {
    val stats = new InMemoryStatsReceiver
    val filter = new PerDstPathStatsFilter[Unit, Unit](stats, mkFilter _)
    val service = filter.andThen(Service.const(Future.Unit))
    await(service(()))
    assert(stats.counters == Map())
  }
}
