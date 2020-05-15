package io.buoyant.router.h2

import java.net.{InetAddress, InetSocketAddress}

import com.twitter.finagle._
import com.twitter.finagle.buoyant.h2.{Method, Request, Response, Stream}
import com.twitter.finagle.ssl.session.{NullSslSessionInfo, SslSessionInfo}
import com.twitter.util.{Future, Time}
import io.buoyant.router.ForwardedHeaderLabeler
import io.buoyant.test.FunSuite

class H2AddForwardedHeaderTest extends FunSuite {
  val OkSvc = Service.mk[Request, Response] { req =>

    val rsp = Response(req.headers, Stream.empty)
    Future.value(rsp)
  }

  val OkStack = H2AddForwardedHeader.module
    .toStack(Stack.leaf(Stack.Role("endpoint"), ServiceFactory.const(OkSvc)))

  def mkReq(authority: String = "svc/name", proto: String = "http") =
    Request(proto, Method.Get, authority, "Some/Path", Stream.empty)

  def service(byl: Option[String] = None, forl: Option[String] = None, req: Request = mkReq()) = {
    val forwardedBy = byl.getOrElse("")
    val forClient = forl.getOrElse("")
    val svc = new H2AddForwardedHeader(() => forwardedBy, () => forClient).andThen(OkSvc)
    svc(req)
  }

  def extractForwardedByHeader(header: String): Map[String, String] = {
    header.split(";").take(2).flatMap { label =>
      label.split("=") match {
        case arr: Array[String] if arr.length == 2 => Some(arr(0) -> arr(1))
        case _ => None
      }
    }.toMap
  }

  test("creates a Forwarded header where none existed") {
    val authority = "svc/name"
    val client = "client:7777"
    val forwardedBy = "_linkerd"
    val proto = "http"
    val rsp = await(service(Some(forwardedBy), Some(client), mkReq(authority, proto)))
    assert(rsp.headers.get("forwarded") == Some(s"by=$forwardedBy;for=$client;authority=$authority;proto=$proto"))
  }

  test("appends to existing Forwarded headers") {
    val request = mkReq()
    request.headers.add("forwarded", "for=8.8.4.4")
    val rsp = await(service(req = request))
    val forwards = rsp.headers.getAll("forwarded")
    assert(forwards.toList == Seq("for=8.8.4.4", "by=;for=;authority=svc/name;proto=http"))
  }

  test("H2AddForwardedHeader.module obfuscates nodes by default") {
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
    val rsp = await(service(mkReq()))
    val forwardedBy = rsp.headers.get("forwarded").getOrElse("")
    val fwdMap = extractForwardedByHeader(forwardedBy)

    val ObfuscatedRE = """^_[A-Za-z0-9._-]+$""".r
    def isObfuscated(v: String) = ObfuscatedRE.unapplySeq(v).isDefined

    (fwdMap.getOrElse("by", ""), fwdMap.getOrElse("for", "")) match {
      case ("", _) => fail("`by` not set")
      case (_, "") => fail("`for` not set")
      case (byl: String, forl: String) =>
        assert(isObfuscated(byl), s"by=$byl is not obfuscated")
        assert(isObfuscated(forl), s"for=$forl is not obfuscated")
        assert(byl != forl)
    }
  }

  test("H2AddForwardedHeader.module uses label overrides") {
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
    val rsp = await(service(mkReq()))
    val forwardedBy = rsp.headers.get("forwarded").getOrElse("")
    val fwdMap = extractForwardedByHeader(forwardedBy)

    (fwdMap.getOrElse("by", ""), fwdMap.getOrElse("for", "")) match {
      case ("", _) => fail("`by` not set")
      case (_, "") => fail("`for` not set")
      case (byl, forl) =>
        assert(byl == "_http")
        // XXX for whatever reason, java.net.Inet6Address doesn't
        // format addresses too intelligently...
        assert(forl == "\"[2001:db8:cafe:0:0:0:0:17]\"")
    }
  }
}
