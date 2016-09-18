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

    def scrub(msg: Message): Unit = {
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
  private def rewriteIfProxy(req: Request): Unit =
    guessAbsolute(req.uri) match {
      case null => // not absolute, nothing to do
      case uri if uri.isAbsolute =>
        req.host = uri.getAuthority
        req.uri = unproxifyUri(uri)
      case _ => // wasn't actually absolute after all
    }

  /**
   * If the uri _looks_ absolute, build a URI. This doesn't have to be
   * perfect, as we can know authoritatively after we've parsed the
   * URI. This lets us forego parsing if the URI doesn't have a scheme.
   *
   * We're using nulls here so we can forego a Some allocation when
   * the URI is absolute.
   */
  private[this] def guessAbsolute(uri: String): URI =
    if (uri.contains("://")) new URI(uri)
    else null

  /**
   * Return only the path, query, and fragment segments of a URI.
   */
  private def unproxifyUri(uri: URI): String =
    new URI(null, null, uri.getPath, uri.getQuery, uri.getFragment).toString

  val filter: Filter[Request, Response, Request, Response] =
    new SimpleFilter[Request, Response] {
      def apply(req: Request, service: Service[Request, Response]) = {
        Headers.scrub(req)
        rewriteIfProxy(req)
        service(req)
      }
    }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = Stack.Role("ProxyRewriteFilter")

      val description = "Rewrites proxied requests as direct requests, " +
        "overriding the Host header and removing canonical proxy headers"

      def make(next: ServiceFactory[Request, Response]) = filter.andThen(next)
    }
}
