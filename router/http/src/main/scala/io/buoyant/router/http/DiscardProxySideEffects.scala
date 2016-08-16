package io.buoyant.router.http

import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import java.net.URI

object DiscardProxySideEffects {

  /**
   * Strips host from request URI and drops Proxy-* headers
   * Those side-effects are added by proxy-aware clients
   */
  class DiscardProxySideEffects extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) = {
      val uri = new URI(req.uri)

      if (uri.isAbsolute) { // Proxied request
        // TODO: HTTP/2.0 support once finagle supports it
        val relativeUri = new URI(null, null, uri.getRawPath, uri.getRawQuery, uri.getRawFragment)
        req.host = uri.getAuthority
        req.uri = relativeUri.toString
      }

      req.headerMap.retain { (k, v) => !k.startsWith("Proxy-") }
      svc(req)
    }
  }

  object DiscardProxySideEffects {
    def apply() = new DiscardProxySideEffects()
  }

  object module extends Stack.Module0[ServiceFactory[Request, Response]] {
    val role = Stack.Role("DiscardProxySideEffects")
    val description = "Strips host from request URI and drops Proxy-* headers"
    def make(next: ServiceFactory[Request, Response]) =
      DiscardProxySideEffects() andThen next
  }
}
