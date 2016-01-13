package io.buoyant.router.http

import com.twitter.finagle.Service
import com.twitter.finagle.http.{Request, RequestProxy, Response}
import com.twitter.util.{Await, Future}
import java.net.{InetAddress, InetSocketAddress}
import org.scalatest.FunSuite

/*
 * This class is required because Request doesn't provide a public constructor that
 * allows setting the remote address.
 */
class SocketOverrideRequestProxy(val request: Request, socket: InetSocketAddress) extends RequestProxy {
  override def remoteSocketAddress = socket
}

class ForwardedFilterTest extends FunSuite {
  val ok = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.contentString = req.headerMap.getAll("Forwarded").mkString("\n")
    Future.value(rsp)
  }

  val service = ForwardedFilter andThen ok
  def mkReq() = {
    new SocketOverrideRequestProxy(Request(), new InetSocketAddress(InetAddress.getByName("8.8.8.8"), 80))
  }

  test("creates a Forwarded header where none existed") {
    val rsp = Await.result(service(mkReq()))
    assert(rsp.contentString == "for:8.8.8.8")
  }

  test("does not create a header for local requests") {
    val rsp = Await.result(service(Request()))
    assert(rsp.contentString.isEmpty)
  }

  test("supports forwarding host") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    val rsp = Await.result(service(req))
    val headers = rsp.contentString.split(";")
    assert(headers contains "host:buoyant.pizza")
  }

  test("supports forwarding proto") {
    val req = mkReq()
    req.uri = "https://buoyant.pizza/"
    val rsp = Await.result(service(req))
    val headers = rsp.contentString.split(";")
    assert(headers contains "proto:https")
  }

  test("appends to existing Forwarded headers") {
    val req = mkReq()
    req.headerMap.add("Forwarded", "for:8.8.4.4")
    val rsp = Await.result(service(req))
    val forwards = rsp.contentString.split("\n")
    assert(forwards.toSeq == Seq("for:8.8.4.4", "for:8.8.8.8"))
  }
}
