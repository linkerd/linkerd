package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.io.Buf
import com.twitter.util.{Future, Var}
import io.buoyant.admin.names.DelegateApiHandler.JsonDelegateTree.Neg
import io.buoyant.config.Parser
import io.buoyant.namer.ConfiguredNamersInterpreter
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.context.{DstBoundCtx, DstPathCtx}
import io.buoyant.test.FunSuite

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
    val filter = new RequestEvaluator(
      EndpointAddr(Address("127.0.0.1", 8081)),
      DstBindingFactory.Namer(ConfiguredNamersInterpreter(Seq())),
      BaseDtab(() => Dtab.empty)
    )
    val client = filter.andThen(testService)
    val req = Request()
    req.headerMap.add("l5d-req-evaluate", "true")
    req.host = "cat"
    Contexts.local.let(DstPathCtx, Dst.Path(Path.Utf8("svc", "cat"))) {
      val resp = await(client(req))
      val resolvedResp = jsonMapper.readValue[EvaluatedRequest](resp.contentString)
      assert(resolvedResp == EvaluatedRequest("/svc/cat", "/127.0.0.1:8081", None, Some(Neg("/svc/cat", None))))
    }
  }

  test("prints out all address sets for endpoints") {
    val addrSet = Var.apply(
      Addr.Bound(Address("1.2.3.4", 8080))
    )
    val filter = new RequestEvaluator(
      EndpointAddr(Address("127.0.0.1", 8081)),
      DstBindingFactory.Namer(ConfiguredNamersInterpreter(Seq())),
      BaseDtab(() => Dtab.empty)
    )
    val client = filter.andThen(testService)
    val req = Request()
    req.headerMap.add("l5d-req-evaluate", "true")
    req.host = "cat"
    Contexts.local.let(DstBoundCtx, Dst.Bound(addrSet, Path.empty)) {
      val resp = await(client(req))
      val resolvedResp = jsonMapper.readValue[EvaluatedRequest](resp.contentString)
      assert(
        resolvedResp == EvaluatedRequest(
          "/",
          "/127.0.0.1:8081",
          Some(Set("/1.2.3.4:8080")),
          Some(Neg("~", None))
        )
      )
    }
  }
}
