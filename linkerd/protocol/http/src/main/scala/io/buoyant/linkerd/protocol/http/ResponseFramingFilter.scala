package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.linkerd.protocol.FramingException

/**
 * A filter that fails badly-framed responses
 */
class ResponseFramingFilter extends SimpleFilter[Request, Response] {
  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] =
    service(request) flatMap { response =>
      val numHeaders = response.headerMap.getAll("Content-Length").toSet.size
      if (numHeaders > 1) {
        // if the response has multiple Content-Length headers with unique
        // values, it's invalid
        Future.exception(FramingException(
          s"$numHeaders conflicting `Content-Length` headers in downstream " +
            "response"
        ))
      } else {
        // otherwise, it's fine
        Future.value(response)
      }
    }

}

object ResponseFramingFilter {

  val role = Stack.Role("ResponseFramingFilter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = ResponseFramingFilter.role
      val description = "Fails badly-framed HTTP responses"
      val filter = new ResponseFramingFilter
      def make(factory: ServiceFactory[Request, Response]) =
        filter.andThen(factory)
    }
}