package io.buoyant.linkerd.protocol.http

import com.twitter.conversions.time._
import com.twitter.finagle._
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future
import io.buoyant.linkerd.protocol.http.ErrorResponder.HttpResponseException
import io.buoyant.router.RoutingFactory
import io.buoyant.test.Awaits
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.ISO_8859_1

import io.buoyant.linkerd.protocol.http.FramingFilter.FramingException
import org.scalatest.FunSuite

class ErrorResponderTest extends FunSuite with Awaits {

  def mkService(rsp: Future[Response]): Service[Request, Response] = {
    val svc = Service.mk[Request, Response] { _ =>
      rsp
    }
    val stk = ErrorResponder.module.toStack(
      Stack.Leaf(Stack.Role("endpoint"), ServiceFactory.const(svc))
    )
    await(stk.make(Stack.Params.empty)())
  }

  val service = mkService(Future.exception(RoutingFactory.UnknownDst(Request(), s"foo\nbar")))

  val writeErrorService = mkService(Future.exception(new WriteException {}))

  val redirectService = mkService {
    val redirect = Response(Status.Found)
    redirect.location = "http://linkerd.io"
    Future.exception(HttpResponseException(redirect))
  }

  val individualTimeoutService = mkService(Future.exception(new IndividualRequestTimeoutException(1.second)))

  val totalTimeoutService = mkService(Future.exception(new GlobalRequestTimeoutException(1.second)))

  val malframedService = mkService(Future.exception(FramingException("malframed!")))

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

  test("returns ServiceUnavailable for request attempt timeouts") {
    val rsp = await(individualTimeoutService(Request()))
    assert(rsp.status == Status.ServiceUnavailable)
  }

  test("returns ServiceUnavailable for total timeouts") {
    val rsp = await(totalTimeoutService(Request()))
    assert(rsp.status == Status.ServiceUnavailable)
  }

  test("returns BadGateway for FramingException") {
    val rsp = await(malframedService(Request()))
    assert(rsp.status == Status.BadGateway)
  }
}
