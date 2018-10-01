package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.stats.InMemoryStatsReceiver
import com.twitter.finagle.{Service, ServiceFactory, Stack, param}
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class StatusCodeStatsFilterTest extends FunSuite with Awaits {

  object Thrown extends Throwable

  test("increments http status code stats on success") {
    val stats = new InMemoryStatsReceiver()
    val svc = Service.mk[Request, Response] { _ =>
      val rep = Response()
      rep.statusCode = 404
      Future.value(rep)
    }
    val stk = StatusCodeStatsFilter.module.toStack(
      Stack.leaf(StatusCodeStatsFilter.role, ServiceFactory.const(svc))
    )
    val service = await(stk.make(Stack.Params.empty + param.Stats(stats))())

    await(service(Request()))
    assert(stats.counters(Seq("status", "404")) == 1)
    assert(stats.counters(Seq("status", "4XX")) == 1)
    assert(stats.stats.isDefinedAt(Seq("time", "4XX")))
  }

  test("treats exceptions as 500 failures") {
    val stats = new InMemoryStatsReceiver()
    val svc = Service.mk[Request, Response] { _ => Future.exception(Thrown) }
    val stk = StatusCodeStatsFilter.module.toStack(
      Stack.leaf(StatusCodeStatsFilter.role, ServiceFactory.const(svc))
    )
    val service = await(stk.make(Stack.Params.empty + param.Stats(stats))())

    intercept[Throwable] { await(service(Request())) }
    assert(stats.counters(Seq("status", "error")) == 1)
    assert(stats.stats.isDefinedAt(Seq("time", "error")))
  }
}
