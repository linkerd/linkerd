package com.twitter.finagle.buoyant

import com.twitter.finagle.Service
import com.twitter.finagle.tracing._
import com.twitter.util.Future
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class TraceInitializerTest extends FunSuite with Awaits {

  case class Payload(var traceId: Option[TraceId])

  class ServerFilter(tracer: Tracer, defaultSampler: Option[Sampler] = None)
    extends TraceInitializer.ServerFilter[Payload, Payload](tracer, defaultSampler) {
    override def traceId(req: Payload): Option[TraceId] = req.traceId
    override def sampler(req: Payload): Option[Sampler] = None
  }

  class ClientFilter(tracer: Tracer) extends TraceInitializer.ClientFilter[Payload, Payload](tracer) {
    override def setContext(req: Payload): Unit = req.traceId = Some(Trace.id)
  }

  def newService(tracer: Tracer, defaultSampler: Option[Sampler] = None) =
    new ServerFilter(tracer, defaultSampler) andThen
      new ClientFilter(tracer) andThen
      new Service[Payload, Payload] {
        override def apply(request: Payload): Future[Payload] = Future.value(request)
      }

  test("does not set sampled on trace when sampler is not present") {
    val service = newService(NullTracer, None)
    val req = Payload(None)
    val rsp = await(service(req))
    assert(rsp.traceId.flatMap(_.sampled).isEmpty)
  }

  test("sets sampled on trace to false when sampler is 0.0") {
    val service = newService(NullTracer, Some(Sampler(0f)))
    val req = Payload(None)
    val rsp = await(service(req))
    assert(rsp.traceId.flatMap(_.sampled).contains(false))
  }

  test("sets sampled on trace to true when sampler is 1.0") {
    val service = newService(NullTracer, Some(Sampler(1f)))
    val req = Payload(None)
    val rsp = await(service(req))
    assert(rsp.traceId.flatMap(_.sampled).contains(true))
  }

  test("parent/child requests are assigned the same trace id") {
    val service = newService(NullTracer, None)
    val req1 = Payload(None)
    val rsp1 = await(service(req1))
    val reqId1 = rsp1.traceId.get
    val req2 = Payload(Some(reqId1))
    val rsp2 = await(service(req2))
    val reqId2 = rsp2.traceId.get

    assert(reqId1.traceId == reqId2.traceId)
    assert(reqId1.spanId != reqId2.spanId)
    assert(reqId1.parentId != reqId2.parentId)
  }

  test("independent requests are assigned different trace ids") {
    val service = newService(NullTracer, None)
    val req1 = Payload(Some(Trace.nextId))
    val rsp1 = await(service(req1))
    val reqId1 = rsp1.traceId.get
    val req2 = Payload(Some(Trace.nextId))
    val rsp2 = await(service(req2))
    val reqId2 = rsp2.traceId.get

    assert(reqId1.traceId != reqId2.traceId)
    assert(reqId1.spanId != reqId2.spanId)
    assert(reqId1.parentId != reqId2.parentId)
  }
}
