package io.buoyant.router.http

import com.twitter.finagle.http.Fields.Via
import com.twitter.finagle.http.Version.{Http10, Http11}
import com.twitter.finagle.http.{Message, Request, Response, Version}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}

/**
 * Appends the [Via] (https://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-14#section-9.9) header to the request and response.
 */
object ViaHeaderAppenderFilter {

  object ViaLinkerd {
    val Linkerd10 = "1.0 linkerd"
    val Linkerd11 = "1.1 linkerd"

    def appendViaHeader(msg: Message): Unit = {
      val extendedVia = msg.headerMap.get(Via) match {
        case Some(x) => s"$x, ${viaLinkerd(msg)}"
        case None => viaLinkerd(msg)
      }
      val _ = msg.headerMap.set(Via, extendedVia)
    }

    private[this] val viaLinkerd: Message => String = { msg =>
      msg.version match {
        case Http10 => Linkerd10
        case Http11 => Linkerd11
        case Version(major, minor) => s"$major.$minor linkerd"
      }
    }
  }

  /**
   * Appends the [VIA] header.
   */
  object filter extends SimpleFilter[Request, Response] {

    def apply(req: Request, svc: Service[Request, Response]) = {
      ViaLinkerd.appendViaHeader(req)

      // Forwards HTTP/1.0 requests using HTTP/1.0 to minimize the chance that the server will send a response that
      // uses HTTP/1.1 features, in particular Transfer-Encoding: chunked, that the HTTP/1.0 application can't handle
      if (req.version == Http11 && req.host.isEmpty) {
        // https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.23
        // If the requested URI does not include an Internet host name for the service being
        // requested, then the Host header field MUST be given with an empty value.
        req.host = ""
      }
      svc(req).map(appendViaHeader)
    }

    private[this] val appendViaHeader: Response => Response = { resp =>
      ViaLinkerd.appendViaHeader(resp)
      resp
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("ViaHeaderAppender")
    val description = "Appends the Via header to the request and response."

    def make(next: ServiceFactory[Request, Response]) =
      filter andThen next
  }

}
