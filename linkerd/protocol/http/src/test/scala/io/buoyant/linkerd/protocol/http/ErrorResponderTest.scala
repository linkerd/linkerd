package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{Service, ServiceFactory, Stack, WriteException}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.io.Charsets
import com.twitter.util.Future
import io.buoyant.linkerd.protocol.http.ErrorResponder.HttpResponseException
import io.buoyant.router.RoutingFactory
import io.buoyant.test.Awaits
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.ISO_8859_1

import io.buoyant.linkerd.protocol.http.FramingFilter.FramingException
import org.scalatest.FunSuite

class ErrorResponderTest extends FunSuite with Awaits {

  val svc = Service.mk[Request, Response] { _ =>
    Future.exception(RoutingFactory.UnknownDst(Request(), s"foo\nbar"))
  }
  val stk = ErrorResponder.serverModule.toStack(
    Stack.Leaf(Stack.Role("endpoint"), ServiceFactory.const(svc))
  )
  val service = await(stk.make(Stack.Params.empty)())

  val writeErrorSvc = Service.mk[Request, Response] { _ =>
    Future.exception(new WriteException {})
  }
  val writeErrorStk = ErrorResponder.serverModule.toStack(
    Stack.Leaf(Stack.Role("endpoint"), ServiceFactory.const(writeErrorSvc))
  )
  val writeErrorService = await(writeErrorStk.make(Stack.Params.empty)())

  val redirectSvc = Service.mk[Request, Response] { _ =>
    val redirect = Response(Status.Found)
    redirect.location = "http://linkerd.io"
    Future.exception(HttpResponseException(redirect))
  }

  val redirectStk = ErrorResponder.serverModule.toStack(
    Stack.Leaf(Stack.Role("endpoint"), ServiceFactory.const(redirectSvc))
  )

  val redirectService = await(redirectStk.make(Stack.Params.empty)())

  test("returns BadRequest for UnknownDst exception") {
    val rsp = await(service(Request()))
    assert(rsp.status == Status.BadRequest)
  }

  test("correctly encodes error headers") {
    val rsp = await(service(Request()))
    val headerErr = rsp.headerMap(Headers.Err.Key)
    assert(!headerErr.contains("\n"))
    assert(headerErr.contains(URLEncoder.encode("\n", ISO_8859_1.toString)))
  }

  test("marks retryable errors with l5d-retryable") {
    val rsp = await(writeErrorService(Request()))
    assert(rsp.status == Status.BadGateway)
    assert(rsp.headerMap(Headers.Retryable.Key) == "true")
  }

  test("respects HttpResponseException") {
    val rsp = await(redirectService(Request()))
    assert(rsp.status == Status.Found)
    assert(rsp.location == Some("http://linkerd.io"))
  }
}
