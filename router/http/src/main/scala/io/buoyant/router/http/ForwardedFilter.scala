package io.buoyant.router.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.http.{Request, Response}
import java.net.{URI, URISyntaxException}

/**
 * Appends the [Forwarded](https://tools.ietf.org/html/rfc7239) header to the request.
 *
 * Possible future additions:
 * * parse incoming X-Forwarded-* from legacy proxy services and convert to Forwarded
 */
object ForwardedFilter extends SimpleFilter[Request, Response] {

  def apply(req: Request, svc: Service[Request, Response]) = {
    val proto = try {
      val uri = new URI(req.uri)
      Option(uri.getScheme)
    } catch {
      case urise: URISyntaxException => None
    }

    val remoteAddress = req.remoteAddress
    if (!remoteAddress.isAnyLocalAddress) {
      val forwarded = Map(
        "for" -> Some(remoteAddress.getHostAddress),
        "host" -> req.host,
        "proto" -> proto
      )
      val v = forwarded.collect { case (k, Some(v)) => s"${k}:${v}" }.mkString(";")
      req.headerMap.add("Forwarded", v)
    }
    svc(req)
  }

  case class Param(enabled: Boolean)
  implicit object Param extends Stack.Param[Param] {
    // The RFC indicates that this feature should be disabled by default.
    val default = Param(false)
  }

  object module extends Stack.Module1[Param, ServiceFactory[Request, Response]] {
    val role = Stack.Role("ForwardedFilter")
    val description = "Adds RFC7239 'Forwarded' headers"
    def make(p: Param, f: ServiceFactory[Request, Response]) =
      if (p.enabled) ForwardedFilter.andThen(f)
      else f
  }
}
