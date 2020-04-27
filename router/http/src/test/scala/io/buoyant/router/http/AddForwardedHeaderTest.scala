package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.{Future, Time}
import io.buoyant.router.ForwardedHeaderLabeler
import io.buoyant.test.FunSuite
import java.net.{InetAddress, InetSocketAddress}

import com.twitter.finagle.ssl.session.{NullSslSessionInfo, SslSessionInfo}

class AddForwardedHeaderTest extends FunSuite {

  val OkSvc = Service.mk[Request, Response] { req =>
    val rsp = Response()
    rsp.contentString = req.headerMap.getAll("Forwarded").mkString("\n")
    Future.value(rsp)
  }

  val OkStack = AddForwardedHeader.module
    .toStack(Stack.leaf(Stack.Role("endpoint"), ServiceFactory.const(OkSvc)))

  def mkReq() = Request()
  def service(byl: String, forl: String, req: Request = mkReq()) = {
    val svc = new AddForwardedHeader(() => byl, () => forl).andThen(OkSvc)
    svc(req)
  }

  test("creates a Forwarded header where none existed") {
    val rsp = await(service("_http", "8.8.8.8", mkReq()))
    assert(rsp.contentString == "by=_http;for=8.8.8.8")
  }

  test("supports forwarding host") {
    val req = mkReq()
    req.host = "buoyant.pizza"
    val rsp = await(service("_cowabunga", "unknown", req))
    val headers =
      assert(rsp.contentString == "by=_cowabunga;for=unknown;host=buoyant.pizza")
  }

  test("supports forwarding proto") {
    val req = mkReq()
    req.uri = "https://buoyant.pizza/"
    val rsp = await(service("_by", "_for", req))
    assert(rsp.contentString == "by=_by;for=_for;proto=https")
  }

  test("appends to existing Forwarded headers") {
    val req = mkReq()
    req.headerMap.add("Forwarded", "for=8.8.4.4")
    val rsp = await(service("_http", "8.8.8.8", req))
    val forwards = rsp.contentString.split("\n")
    assert(forwards.toSeq == Seq("for=8.8.4.4", "by=_http;for=8.8.8.8"))
  }

  test("AddForwardedHeader.module obfuscates nodes by default") {
    val params = Stack.Params.empty + ForwardedHeaderLabeler.Enabled(true)
    val factory = OkStack.make(params)
    val conn = new ClientConnection {
      override val remoteAddress = new InetSocketAddress(InetAddress.getLocalHost, 43241)
      override val localAddress = new InetSocketAddress(InetAddress.getLocalHost, 8080)
      override def close(d: Time) = Future.Unit
      override def onClose = Future.never

      override def sslSessionInfo: SslSessionInfo = NullSslSessionInfo
    }
    val service = await(factory(conn))
    val rsp = await(service(Request()))
    val forwards = rsp.contentString.split("\n").map { hop =>
      hop.split(";").flatMap { param =>
        param.split("=", 2) match {
          case Array(k, v) => Some(k -> v)
          case _ => None
        }
      }.toMap
    }
    val ObfuscatedRE = """^_[A-Za-z0-9._-]+$""".r
    def isObfuscated(v: String) = ObfuscatedRE.unapplySeq(v).isDefined
    assert(forwards.length == 1)
    val fwd = forwards(0)

    (fwd.get("by"), fwd.get("for")) match {
      case (None, _) => fail("`by` not set")
      case (_, None) => fail("`for` not set")
      case (Some(byl), Some(forl)) =>
        assert(isObfuscated(byl), s"by=$byl is not obfuscated")
        assert(isObfuscated(forl), s"for=$forl is not obfuscated")
        assert(byl != forl)
    }
  }

  test("AddForwardedHeader.module uses label overrides") {
    val params = Stack.Params.empty +
      ForwardedHeaderLabeler.Enabled(true) +
      ForwardedHeaderLabeler.By(ForwardedHeaderLabeler.ObfuscatedStatic("http")) +
      ForwardedHeaderLabeler.For(ForwardedHeaderLabeler.ClearIp)
    val factory = OkStack.make(params)
    val conn = new ClientConnection {
      override val remoteAddress = new InetSocketAddress("2001:db8:cafe::17", 43241)
      override val localAddress = new InetSocketAddress(InetAddress.getLocalHost, 8080)
      override def close(d: Time) = Future.Unit
      override def onClose = Future.never
      override def sslSessionInfo: SslSessionInfo = NullSslSessionInfo
    }
    val service = await(factory(conn))
    val rsp = await(service(Request()))
    val forwards = rsp.contentString.split("\n").map { hop =>
      hop.split(";").flatMap { param =>
        param.split("=", 2) match {
          case Array(k, v) => Some(k -> v)
          case _ => None
        }
      }.toMap
    }
    assert(forwards.length == 1)
    val fwd = forwards(0)

    (fwd.get("by"), fwd.get("for")) match {
      case (None, _) => fail("`by` not set")
      case (_, None) => fail("`for` not set")
      case (Some(byl), Some(forl)) =>
        assert(byl == "_http")
        // XXX for whatever reason, java.net.Inet6Address doesn't
        // format addresses too intelligently...
        assert(forl == "\"[2001:db8:cafe:0:0:0:0:17]\"")
    }
  }
}
