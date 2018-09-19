package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Method, Request, Response, Status, Stream}
import com.twitter.finagle.context.Contexts
import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.util.{Future, Var}
import io.buoyant.router.RouterLabel
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}
import io.buoyant.test.FunSuite
import io.buoyant.test.h2.StreamTestUtils

class H2DiagnosticTracerTest extends FunSuite {
  private [this] val MaxForwards = "max-forwards"
  private[this] val testService = Service.mk[Request, Response] { req =>
    val rsp = Response(Status.Ok, Stream.const("request received"))
    Future.value(rsp)
  }
  private[this] def mkTracerH2Request(headers:(String, String)*): Request = {
    val req = Request("http", Method.Trace, "svc","/", Stream.const("request received"))
    headers.foreach{header => req.headers.set(header._1, header._2)}
    req
  }

  private[this] val testStack = H2DiagnosticTracer.module +:
    Stack.leaf(Stack.Role("endpoint"), ServiceFactory.const(testService))

  test("pass through TRACE requests without l5d-add-context header") {
    val serviceFactory = testStack.make(Stack.Params.empty)
    val resp = await(serviceFactory.toService(mkTracerH2Request()))
    assert(await(StreamTestUtils.readDataString(resp.stream)) == "request received")
  }

  test("returns 'bad request' response when unparsable Max-Forwards header is sent"){
    val serviceFactory = testStack.make(Stack.Params.empty)
    val service = serviceFactory.toService
    val req = mkTracerH2Request((MaxForwards, "31qe"), ("l5d-add-context", "true"))
    val resp = await(service(req))
    assert(resp.status == Status.BadRequest)
  }

  test("returns response with no body when Max-Forwards = 0 and l5d-add-context is absent"){
    val serviceFactory = testStack.make(Stack.Params.empty)
    val service = serviceFactory.toService
    val req = mkTracerH2Request((MaxForwards, "31qe"))
    val resp = await(service(req))
    val dataStream = await(StreamTestUtils.readDataString(resp.stream))
    assert(dataStream == "Invalid value for max-forwards header")
  }

  test("returns client and service name"){
    val addrSet = Var.apply(Addr.Bound(Address("1.2.3.4", 8080)))
    val boundPath = Path.Utf8("client", "name")
    val pathCtx = Path.Utf8("svc","cat")
    Contexts.local.let(Seq(
      Contexts.local.KeyValuePair(DstPathCtx, Dst.Path(pathCtx)),
      Contexts.local.KeyValuePair(DstBoundCtx, Dst.Bound(addrSet, boundPath)))
    ){
      val serviceFactory = testStack.make(Stack.Params.empty)
      val client = serviceFactory.toService
      val resp = await(client(mkTracerH2Request(("Max-Forwards", "1"), ("l5d-add-context", "true"))))
      val dataStream = await(StreamTestUtils.readDataString(resp.stream))
      assert(dataStream.contains(s"service name: ${pathCtx.show}"))
      assert(dataStream.contains(s"client name: ${boundPath.show}"))
    }
  }

  test("returns selected endpoint ip address") {
    val endpointAddr = EndpointAddr(Address("127.0.0.1", 8081))
    val serviceFactory = testStack.make(
      Stack.Params.empty + endpointAddr + RouterLabel.Param("routerLabel")
    )

    Contexts.local.let(DstPathCtx, Dst.Path(Path.empty)) {
      val client = serviceFactory.toService
      val resp = await(client(mkTracerH2Request(("Max-Forwards", "1"), ("l5d-add-context", "true"))))
      val dataStream = await(StreamTestUtils.readDataString(resp.stream))
      assert(dataStream.contains(s"selected address: 127.0.0.1:8081"))
    }
  }

  test("returns endpoints set") {
    val addrSet = Var.apply(
      Addr.Bound(Address("1.2.3.4", 8080))
    )

    Contexts.local.let(DstBoundCtx, Dst.Bound(addrSet, Path.empty)) {
      val serviceFactory = testStack.make(Stack.Params.empty)
      val client = serviceFactory.toService
      val resp = await(client(mkTracerH2Request(("Max-Forwards", "1"), ("l5d-add-context", "true"))))
      val dataStream = await(StreamTestUtils.readDataString(resp.stream))
      assert(dataStream.contains("addresses: [1.2.3.4:8080]"))
    }
  }
}
