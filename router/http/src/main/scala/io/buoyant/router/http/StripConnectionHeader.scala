package io.buoyant.router.http

import com.twitter.finagle.http.Fields.Connection
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}

object StripConnectionHeader {

  /**
   * Removes the 'Connection' and header and any header it list from (i.e. downstream) requests.
   */
  object filter extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      val connection = req.headerMap.get(Connection).getOrElse("none")
      if (!(connection.toLowerCase == "upgrade")) {
        val headersListedInConnection: Seq[String] = req.headerMap.remove(Connection) match {
          case Some(s) => s.split(",").map(_.trim).filter(_.nonEmpty)
          case None => Nil
        }
        headersListedInConnection.foreach(req.headerMap.remove(_))
      }
      svc(req)
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("StripConnectionHeader")
    val description = "Removes the 'Connection' header and any header it list from requests"

    def make(next: ServiceFactory[Request, Response]) =
      filter andThen next
  }

}
