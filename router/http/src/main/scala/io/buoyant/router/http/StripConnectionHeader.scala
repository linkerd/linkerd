package io.buoyant.router.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.http.{Request, Response}

object StripConnectionHeader {

  val Key = "Connection"

  /**
   * Removes the 'Connection' header from (i.e. downstream) requests.
   */
  object filter extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      req.headerMap.remove(Key)
      svc(req)
    }
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("StripConnectionHeader")
    val description = "Removes the 'Connection' header from requests"
    def make(next: ServiceFactory[Request, Response]) =
      filter andThen next
  }
}
