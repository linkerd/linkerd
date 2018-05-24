package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.namer.{DelegateTree, Delegator}
import io.buoyant.router.RouterLabel
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}
import io.buoyant.test.FunSuite

class EvaluatorNamer(delegation: DelegateTree[Name.Bound]) extends NameInterpreter with Delegator {
  def delegate(
    dtab: Dtab,
    tree: NameTree[Name.Path]
  ): Future[DelegateTree[Name.Bound]] = Future.value(delegation)

  def dtab: Activity[Dtab] = Activity.pending

  override def bind(
    dtab: Dtab,
    path: Path
  ): Activity[NameTree[Name.Bound]] = Activity.pending
}

class RequestEvaluatorTest extends FunSuite {

  private[this] val successMessage = "request succeeded"
  private[this] val testService = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.contentString = successMessage
    Future.value(rsp)
  }

  private[this] val testStack =  RequestEvaluator.module +:
    Stack.Leaf(Stack.Role("endpoint"), ServiceFactory.const(testService))

  private[this] def mkTracerRequest = {
    val req = Request()
    req.method = Method.Trace
    req.headerMap.add("l5d-max-depth", "1")
    req
  }

  test("lets requests without TRACE method to pass through") {
    val req = Request()
    val serviceFactory = testStack.make(Stack.Params.empty)
    val resp = await(serviceFactory.toService(req))
    assert(resp.contentString == successMessage)
  }

  test("prints client and service name"){
    val addrSet = Var.apply(Addr.Bound(Address("1.2.3.4", 8080)))
    val boundPath = Path.Utf8("client", "name")
    val pathCtx = Path.Utf8("svc","cat")
    Contexts.local.let(Seq(
      Contexts.local.KeyValuePair(DstPathCtx, Dst.Path(pathCtx)),
      Contexts.local.KeyValuePair(DstBoundCtx, Dst.Bound(addrSet, boundPath)))
    ){
      val serviceFactory = testStack.make(Stack.Params.empty)
      val client = serviceFactory.toService
      val resp = await(client(mkTracerRequest))
      assert(resp.contentString.contains(s"service name: ${pathCtx.show}"))
      assert(resp.contentString.contains(s"client name: ${boundPath.show}"))
    }

  }

  test("prints selected endpoint ip address") {
    val endpointAddr = EndpointAddr(Address("127.0.0.1", 8081))
    val serviceFactory = testStack.make(
      Stack.Params.empty + endpointAddr + RouterLabel.Param("routerLabel")
    )

    Contexts.local.let(DstPathCtx, Dst.Path(Path.empty)) {
      val client = serviceFactory.toService
      val resp = await(client(mkTracerRequest))
      assert(resp.contentString.contains(s"selected address: 127.0.0.1:8081"))
    }
  }

  test("prints endpoints set") {
    val addrSet = Var.apply(
      Addr.Bound(Address("1.2.3.4", 8080))
    )

    Contexts.local.let(DstBoundCtx, Dst.Bound(addrSet, Path.empty)) {
      val serviceFactory = testStack.make(Stack.Params.empty)
      val client = serviceFactory.toService
      val resp = await(client(mkTracerRequest))
      assert(resp.contentString.contains("addresses: [1.2.3.4:8080]"))
    }
  }
}
