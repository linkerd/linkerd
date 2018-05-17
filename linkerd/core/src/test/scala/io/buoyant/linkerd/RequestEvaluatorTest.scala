package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{MediaType, Request, Response}
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future, Var}
import io.buoyant.config.Parser
import io.buoyant.namer.{DelegateTree, Delegator}
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
  val jsonMapper = Parser.jsonObjectMapper(Nil)

  val successMessage = "request succeeded"
  val testService = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.content = Buf.Utf8(successMessage)
    Future.value(rsp)
  }

  val testStack = DstPathCtx.Setter.module.toStack(
    RequestEvaluator.module.toStack(
      Stack.Leaf(Stack.Role("endpoint"), ServiceFactory.const(testService))
    )
  )

  test("lets requests without the required header to pass through") {
    val req = Request()
    val service = testStack.make(Stack.Params.empty)
    val resp = await(service.toService(req))

    assert(resp.contentString == successMessage)
  }

  test("prints out request identification ") {
    val addrSet = Var.apply(
      Addr.Bound(Address("1.2.3.4", 8080))
    )
    val path = Path.Utf8("io.l5d.fs", "cat")
    val filter = new RequestEvaluator(
      EndpointAddr(Address("127.0.0.1", 8081)),
      DstBindingFactory.Namer(new EvaluatorNamer(DelegateTree.Leaf(path, Dentry.nop, Name.Bound(addrSet, path)))),
      BaseDtab(() => Dtab.empty)
    )
    val client = filter.andThen(testService)
    val req = Request()
    req.headerMap.add("l5d-req-evaluate", "true")
    req.host = "cat"
    req.contentType = MediaType.Json
    Contexts.local.let(DstPathCtx, Dst.Path(path)) {
      val resp = await(client(req))
      val resolvedResp = jsonMapper.readValue[EvaluatedRequest](resp.contentString)
      assert(resolvedResp == EvaluatedRequest(path.show, "/127.0.0.1:8081", None, List(path.show)))
    }
  }

  test("prints out all address sets for endpoints") {
    val addrSet = Var.apply(
      Addr.Bound(Address("1.2.3.4", 8080))
    )
    val path = Path.Utf8("io.l5d.fs", "cat")
    val filter = new RequestEvaluator(
      EndpointAddr(Address("127.0.0.1", 8081)),
      DstBindingFactory.Namer(new EvaluatorNamer(DelegateTree.Leaf(path, Dentry.nop, Name.empty))),
      BaseDtab(() => Dtab.empty)
    )
    val client = filter.andThen(testService)
    val req = Request()
    req.headerMap.add("l5d-req-evaluate", "true")
    req.host = "cat"
    req.contentType = MediaType.Json
    Contexts.local.let(DstBoundCtx, Dst.Bound(addrSet, Path.empty)) {
      val resp = await(client(req))
      val resolvedResp = jsonMapper.readValue[EvaluatedRequest](resp.contentString)
      assert(
        resolvedResp == EvaluatedRequest(
          path.show,
          "/127.0.0.1:8081",
          Some(Set("/1.2.3.4:8080")),
          List(path.show)
        )
      )
    }
  }
}
