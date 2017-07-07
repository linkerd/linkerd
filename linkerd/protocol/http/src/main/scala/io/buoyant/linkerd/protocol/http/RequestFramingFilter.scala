package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.logging.Logger
import com.twitter.util.Future

/**
 * A filter that fails badly-framed requests.
 */
class RequestFramingFilter extends SimpleFilter[Request, Response] {
  private[this] val log = Logger.get("RequestFramingFilter")

  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] =
    if (request.headerMap.getAll("Content-Length").toSet.size > 1) {
      // if the length of the Content-Length key in the request's header map
      // is greater than 1, then there are duplicate values.
      log.error("request with duplicate Content-Length headers", request)
      val resp = Headers.Err.respond(
        "Request contained duplicate `Content-Length` headers",
        Status.BadRequest
      )
      Future(resp)
    } else {
      // otherwise, the request is fine!
      service(request)
    }

}

object RequestFramingFilter {

  val role = Stack.Role("RequestFramingFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = RequestFramingFilter.role
      val description = "Fails badly-framed HTTP requests"
      val filter = new RequestFramingFilter
      def make(factory: ServiceFactory[Request, Response]) =
        filter.andThen(factory)
    }
}
