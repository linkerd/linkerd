package io.buoyant.router.http

import com.twitter.finagle.http.Fields._
import com.twitter.finagle.http.{Message, Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}

object StripHopByHopHeadersFilter {

  object HopByHopHeaders {

    def scrub(msg: Message): Unit = {
      val headers = msg.headerMap
      headers.remove(ProxyAuthenticate)
      headers.remove(ProxyAuthorization)
      headers.remove(Te)
      headers.remove(Trailer)
      headers.remove(TransferEncoding)
      headers.remove(Upgrade)

      val headersListedInConnection: Seq[String] = headers.remove(Connection) match {
        case Some(s) => s.split(",").map(_.trim).filter(_.nonEmpty).toIndexedSeq
        case None => Nil
      }
      headersListedInConnection.foreach(headers.remove(_))
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

    private[this] val scrubResponse: Response => Response = { resp =>
      HopByHopHeaders.scrub(resp)
      resp
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("StripHopByHopHeadersFilter")
    val description = "Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests"

    def make(next: ServiceFactory[Request, Response]) =
      filter andThen next
  }

}

