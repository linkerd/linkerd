package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Fields, Request, Response, HeaderMap}
import com.twitter.util.Future
import java.net.{Inet4Address, Inet6Address, InetSocketAddress, SocketAddress, URI, URISyntaxException}
import scala.collection.mutable
import scala.util.Random
import com.twitter.logging.Logger
import org.apache.commons.lang.StringUtils
import com.google.common.base.CharMatcher

/**
 * Applies the Host value in the [Forwarded](https://tools.ietf.org/html/rfc7239) header to the Request's Host header.
 * So the Request is sent with the actual host value.
 */
object ApplyHostForwardedHeader {

  object filter extends SimpleFilter[Request, Response] {
    private val log = Logger(getClass)

    val ForwardedHeader = "Forwarded"
    val HostElementPrefix = "host="
    def apply(req: Request, svc: Service[Request, Response]): Future[Response] = {

      replaceHostWithForwardedHostIfExists(req)

      svc(req)
    }

    private def getForwardedHost(headerMap: HeaderMap): Option[String] = {
      headerMap.get(ForwardedHeader)
        .flatMap { f =>
          f.split(";|,").toStream
            .map(_.trim)
            .find(x => StringUtils.startsWithIgnoreCase(x, HostElementPrefix))
        }
        .map(fHost => CharMatcher.anyOf("'\"").trimFrom(fHost.toLowerCase().replace(HostElementPrefix, "")))
    }

    private def replaceHostWithForwardedHostIfExists(req: Request): Any = {
      val forwardedHostOp = getForwardedHost(req.headerMap)
      forwardedHostOp.foreach(forwardedHost => {
        log.info("Replace Host: %s with %s", req.host, forwardedHost)
        req.headerMap.set(Fields.Host, forwardedHost)
      })
    }
  }
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = Stack.Role("ApplyHostForwardedHeader")
      val description = "Applies the Host value in the [Forwarded](https://tools.ietf.org/html/rfc7239) header to the Request's Host header"
      def make(
        next: ServiceFactory[Request, Response]
      ) = filter andThen next
    }
}

