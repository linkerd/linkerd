package io.buoyant.router

import com.twitter.finagle.{Filter, Path, Service, ServiceFactory, Stack, StackBuilder, param}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.stack.{Endpoint, nilStack}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, NullStatsReceiver, StatsReceiver}
import com.twitter.util.{Future, Local}
import io.buoyant.test.FunSuite

class NotDog extends Exception
class DangCat extends Exception("meow", new NotDog)

class PerDstPathStatsFilterTest extends FunSuite {

  def setContext(f: String => Path) =
    Filter.mk[String, Unit, String, Unit] { (req, service) =>
      val save = Local.save()
      try Contexts.local.let(ctx.DstPath, Dst.Path(f(req))) { service(req) }
      finally Local.restore(save)
    }

  val service = Service.mk[String, Unit] {
    case "cat" => Future.exception(new DangCat)
    case _ => Future.Unit
  }

  val stack = {
    val sf = ServiceFactory(() => Future.value(service))
    val stk = new StackBuilder[ServiceFactory[String, Unit]](nilStack)
    stk.push(PerDstPathStatsFilter.module[String, Unit])
    stk.result ++ Stack.Leaf(Endpoint, sf)
  }

  test("module installs a per-path StatsFilter") {
    val stats = new InMemoryStatsReceiver
    val params = Stack.Params.empty + param.Stats(stats.scope("pfx"))
    val ctxFilter = setContext(Path.Utf8("req", _))
    val factory = ctxFilter.andThen(stack.make(params))
    val service = await(factory())

    await(service("dog"))
    assert(await(service("cat").liftToTry).isThrow)
    await(service("dog"))

    val pfx = Seq("pfx", "dst/path")
    val catPfx = pfx :+ "req/cat"
    val dogPfx = pfx :+ "req/dog"
    assert(stats.counters == Map(
      (catPfx :+ "requests") -> 1,
      (catPfx :+ "failures") -> 1,
      (catPfx :+ "failures" :+ "io.buoyant.router.DangCat") -> 1,
      (catPfx :+ "failures" :+ "io.buoyant.router.DangCat" :+ "io.buoyant.router.NotDog") -> 1,
      (dogPfx :+ "requests") -> 2,
      (dogPfx :+ "success") -> 2
    ))
    assert(stats.histogramDetails.keys == Set(
      "pfx/dst/path/req/cat/request_latency_ms",
      "pfx/dst/path/req/dog/request_latency_ms"
    ))
    assert(stats.gauges.keys == Set(
      (catPfx :+ "pending"),
      (dogPfx :+ "pending")
    ))
  }

  test("module does nothing when DstPath context not set") {
    val stats = new InMemoryStatsReceiver
    val params = Stack.Params.empty + param.Stats(stats.scope("pfx"))
    val factory = stack.make(params)
    val service = await(factory())

    Contexts.local.letClear(ctx.DstPath) {
      await(service("dog"))
      assert(await(service("cat").liftToTry).isThrow)
      await(service("dog"))
    }

    assert(stats.counters.isEmpty)
    assert(stats.histogramDetails.isEmpty)
    assert(stats.gauges.isEmpty)
  }

  test("module does nothing when DstPath context isEmpty") {
    val stats = new InMemoryStatsReceiver
    val params = Stack.Params.empty + param.Stats(stats.scope("pfx"))
    val ctxFilter = setContext(_ => Path.empty)
    val factory = ctxFilter.andThen(stack.make(params))
    val service = await(factory())

    Contexts.local.letClear(ctx.DstPath) {
      await(service("dog"))
      assert(await(service("cat").liftToTry).isThrow)
      await(service("dog"))
    }

    assert(stats.counters.isEmpty)
    assert(stats.histogramDetails.isEmpty)
    assert(stats.gauges.isEmpty)
  }

}
