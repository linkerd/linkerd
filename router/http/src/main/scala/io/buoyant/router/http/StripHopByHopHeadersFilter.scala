package io.buoyant.router.http

import com.twitter.finagle.http.Fields._
import com.twitter.finagle.http.{HeaderMap, Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}

object StripHopByHopHeadersFilter {
  val HopByHopHeaders = Seq(
    Connection,
    ProxyAuthenticate,
    ProxyAuthorization,
    Te,
    Trailer,
    TransferEncoding,
    Upgrade
  )

  /**
   * Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests.
   */
  object filter extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      removeHopByHopHeaders(req.headerMap)
      svc(req) map { resp =>
        removeHopByHopHeaders(resp.headerMap)
        resp
      }
    }

    private def removeHopByHopHeaders(headerMap: HeaderMap): Unit = {
      HopByHopHeaders.foreach(h => {
        h match {
          case Connection =>
            val headersListedInConnection: Seq[String] = headerMap.remove(h) match {
              case Some(s) => s.split(",").map(_.trim).filter(_.nonEmpty)
              case None => Nil
            }
            headersListedInConnection.foreach(headerMap.remove(_))
          case _ => headerMap.remove(h)
        }
      })
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("StripHopByHopHeadersFilter")
    val description = "Removes all Hop-by-Hop headers and any header listed in `Connection` header from requests"

    def make(next: ServiceFactory[Request, Response]) =
      filter andThen next
  }

}

