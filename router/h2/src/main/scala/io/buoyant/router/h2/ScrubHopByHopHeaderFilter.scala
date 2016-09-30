package io.buoyant.router.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.buoyant.h2.{Message, Request, Response}

object ScrubHopByHopHeadersFilter {

  object HopByHopHeaders {
    val Connection = "connection"
    val ProxyAuthenticate = "proxy-authenticate"
    val ProxyAuthorization = "proxy-authorization"
    val ProxyConnection = "proxy-connection"
    val Te = "te"
    val Trailer = "trailer"
    val TransferEncoding = "transfer-encoding"
    val Upgrade = "upgrade"

    def scrub(msg: Message): Unit = {
      msg.headers.remove(ProxyAuthenticate)
      msg.headers.remove(ProxyAuthorization)
      msg.headers.remove(Te)
      msg.headers.remove(Trailer)
      msg.headers.remove(TransferEncoding)
      msg.headers.remove(Upgrade)
      for {
        conn <- msg.headers.remove(Connection) ++ msg.headers.remove(ProxyConnection)
        header <- conn.split(", ")
      } {
        val h = header.trim
        if (h.nonEmpty) {
          msg.headers.remove(h); ()
        }
      }
    }
  }

  /**
   * Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests.
   */
  object filter extends SimpleFilter[Request, Response] {

    def apply(req: Request, svc: Service[Request, Response]) = {
      HopByHopHeaders.scrub(req)
      svc(req).map(scrubResponse)
    }

    private[this] val scrubResponse: Response => Response = { rsp =>
      HopByHopHeaders.scrub(rsp)
      rsp
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("ScrubHopByHopHeadersFilter")
    val description =
      "Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests"

    def make(next: ServiceFactory[Request, Response]) =
      filter.andThen(next)
  }

}

