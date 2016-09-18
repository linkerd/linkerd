package io.buoyant.router.http

import com.twitter.finagle.http.Fields._
import com.twitter.finagle.http.{Message, Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}

object StripHopByHopHeadersFilter {

  object HopByHopHeaders {
    val Headers = Seq(
      Connection,
      ProxyAuthenticate,
      ProxyAuthorization,
      Te,
      Trailer,
      TransferEncoding,
      Upgrade
    )

    def scrub(msg: Message): Unit = {
      Headers.foreach {
        case h@Connection =>
          val headersListedInConnection: Seq[String] = msg.headerMap.remove(h) match {
            case Some(s) => s.split(",").map(_.trim).filter(_.nonEmpty)
            case None => Nil
          }
          headersListedInConnection.foreach(msg.headerMap.remove(_))
        case h => msg.headerMap.remove(h)
      }
    }
  }

  /**
   * Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests.
   */
  object filter extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      HopByHopHeaders.scrub(req)
      svc(req) map { resp =>
        HopByHopHeaders.scrub(resp)
        resp
      }
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("StripHopByHopHeadersFilter")
    val description = "Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests"

    def make(next: ServiceFactory[Request, Response]) =
      filter andThen next
  }

}

