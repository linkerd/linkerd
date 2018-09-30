package io.buoyant.router.http.stats

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{Method, Request, Response, Status}
import com.twitter.finagle.stats.{InMemoryStatsReceiver, StatsReceiver}
import com.twitter.finagle.{Filter, Path, Service}
import com.twitter.util.{Future, Local}
import io.buoyant.router.context.DstPathCtx
import io.buoyant.router.http.stats.Src.SrcServiceHeader
import io.buoyant.test.Awaits
import org.scalatest.{fixture, Outcome}

class ServiceToServiceStatsFilterTest extends fixture.FunSuite with Awaits {

  case class F(svc: Service[Request, Response], stats: InMemoryStatsReceiver)

  type FixtureParam = Service[Request, Response] => F

  def setContext(f: Request => Path) =
    Filter.mk[Request, Response, Request, Response] { (req, service) =>
      val save = Local.save()
      try Contexts.local.let(DstPathCtx, Dst.Path(f(req))) {
        service(req)
      } finally Local.restore(save)
    }

  def withFixture(test: OneArgTest): Outcome = {
    val stats     = new InMemoryStatsReceiver
    val filter    = new ServiceToServiceStatsFilter(stats)
    val ctxFilter = setContext((req: Request) => Path.Utf8("dst-service"))

    def service(backend: Service[Request, Response]) =
      ctxFilter.andThen(filter).andThen(backend)
    val save = Local.save()
    try {
      test(be => F(service(be), stats))
    } finally Local.restore(save)
  }

  def mkService(sr: StatsReceiver) = {
    val filter = new ServiceToServiceStatsFilter(sr)
    filter.andThen(Service.const[Response](Future.value(Response.apply(Status.Ok))))
  }

  val scope = Seq("route", "src-service", "/dst-service", "GET")

  test("reports service-to-service latency stats") { f =>
    val origin = Service.const[Response](Future.value(Response(Status.Ok)))
    val proxy  = f(origin)
    val req    = Request(Method.Get, "/")
    req.headerMap.add(SrcServiceHeader, "src-service")
    Future.collect((1 to 10).map(_ => proxy.svc(req)))

    assert(proxy.stats.counters.get(scope :+ "requests") == (Some(10)))
    assert(proxy.stats.stats.get(scope :+ "request_latency_ms").map(_.size) == (Some(10)))
  }

  test("reports service-to-service errors stats") { f =>
    val origin = Service.const[Response](Future.exception(new Exception("oops")))
    val proxy  = f(origin)
    val req    = Request(Method.Get, "/")
    req.headerMap.add(SrcServiceHeader, "src-service")
    Future.collect((1 to 10).map(_ => proxy.svc(req)))

    assert(proxy.stats.counters.get(scope :+ "requests") == (Some(10)))
    assert(proxy.stats.counters.get(scope :+ "status" :+ "5XX") == (Some(10)))

  }
}
