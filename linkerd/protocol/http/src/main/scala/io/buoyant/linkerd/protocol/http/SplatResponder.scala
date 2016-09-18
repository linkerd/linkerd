package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.logging.Logger
import com.twitter.util.Future

class SplatResponder extends SimpleFilter[Request, Response] {
  private[this] val log = Logger.get("SplatResponderFilter")

  def apply(req: Request, service: Service[Request, Response]) = {
    service(req).flatMap(addHeadersAfterFn)
  }

  private[this] def addHeadersAfter(rsp: Response) = Future {
    val connection = rsp.headerMap.get("Connection").getOrElse("none")
    System.err.format("In SplatResponder: value of Connection header after request service: %s\n", connection)
    val upgrade = rsp.headerMap.get("Upgrade").getOrElse("none")
    if (upgrade.toLowerCase == "websocket") {
      System.err.println("ADDING After Connection header: Upgrade")
      rsp.headerMap.set("After-Connection", "Upgrade")
      rsp.headerMap.set("Connection", "Upgrade")
    } else {
      System.err.println("NOT adding After Connection header")
      rsp.headerMap.set("After-Connection", "NoUpgrade")
    }
    rsp
  }

  private[this] val addHeadersAfterFn: Response => Future[Response] = addHeadersAfter _
}

object SplatResponder {
  val role = Stack.Role("SplatResponder")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = SplatResponder.role
      val description = "Modifies response headers to make things work"
      val filter = new SplatResponder
      def make(factory: ServiceFactory[Request, Response]) =
        filter.andThen(factory)
    }
}