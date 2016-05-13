package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{Service, ServiceFactory, Stack}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.util.Future
import com.twitter.finagle.http.{Request, Response, Status}
import java.net.URLEncoder
import io.buoyant.router.RoutingFactory
import io.buoyant.test.Awaits
import org.scalatest.FunSuite

class ErrorResponderTest extends FunSuite with Awaits {

  val svc = Service.mk[Request, Response] { _ =>
    Future.exception(RoutingFactory.UnknownDst(Request(), new Exception(s"foo\nbar")))
  }
  val stk = ErrorResponder.toStack(
    Stack.Leaf(ErrorResponder.role, ServiceFactory.const(svc))
  )
  val service = await(stk.make(Stack.Params.empty)())

  test("returns BadRequest for UnknownDst exception") {
    val rsp = await(service(Request()))
    assert(rsp.status == Status.BadRequest)
  }

  test("correctly encodes error headers") {
    val rsp = await(service(Request()))
    val headerErr = rsp.headerMap(Headers.Err)
    assert(!headerErr.contains("\n"))
    assert(headerErr.contains(URLEncoder.encode("\n", "ISO-8859-1")))
  }
}
