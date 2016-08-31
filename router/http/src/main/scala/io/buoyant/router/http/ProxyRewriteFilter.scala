package io.buoyant.router.http

import com.twitter.finagle.{Filter, Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.http.{Request, Response, Message}
import java.net.URI

/**
 * Coerces HTTP proxy requests to normal requests.
 */
object ProxyRewriteFilter {

  /**
   * There are three well-known Proxy- headers must be removed from
   * all requests.
   */
  object Headers {
    val ProxyConnection = "proxy-connection"
    val ProxyAuthenticate = "proxy-authenticate"
    val ProxyAuthorization = "proxy-authorization"

    def strip(msg: Message): Unit = {
      msg.headerMap.remove(ProxyConnection)
      msg.headerMap.remove(ProxyAuthenticate)
      val _ = msg.headerMap.remove(ProxyAuthorization)
    }
  }

  /**
   * If the original URI is absolute
   * (e.g. scheme://host/path?query#fragment), drop the scheme, use
   * the authority to set the request's Host header, and rewrite the
   * URI to the remaining path, query, and fragment.
   */
  private def rewriteIfProxy(req: Request): Unit = {
    val uri = new URI(req.uri)
    if (uri.isAbsolute) {
      req.host = uri.getAuthority
      req.uri = unproxifyUri(uri)
    }
  }

  /**
   * Return only the path, query, and fragment segments of a URI.
   */
  private def unproxifyUri(orig: URI): String =
    if (!orig.isAbsolute) orig.toString
    else new URI(null, null, orig.getPath, orig.getQuery, orig.getFragment).toString

  /**
   * Strips host from request URI and drops Proxy-* headers
   * Those side-effects are added by proxy-aware clients
   */
  val filter: Filter[Request, Response, Request, Response] =
    new SimpleFilter[Request, Response] {
      def apply(req: Request, svc: Service[Request, Response]) = {
        Headers.strip(req)
        rewriteIfProxy(req)
        svc(req)
      }
    }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = Stack.Role("ProxyRewriteFilter")
      val description = "Strips host from request URI and drops Proxy-* headers"
      def make(next: ServiceFactory[Request, Response]) = filter.andThen(next)
    }
}
