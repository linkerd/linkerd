package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.logging.Logger
import com.twitter.util.Future
import io.buoyant.linkerd.ProtocolException

object FramingFilter {
  /**
    * A filter that fails badly-framed requests.
    */
  class ServerFilter extends SimpleFilter[Request, Response] {
    private[this] val log = Logger.get("RequestFramingFilter")

    override def apply(request: Request,
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
        Future.value(resp)
      } else {
        // otherwise, the request is fine!
        service(request)
      }

  }

  object ServerFilter {

    val role = Stack.Role("RequestFramingFilter")
    val module: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module0[ServiceFactory[Request, Response]] {
        val role = ServerFilter.role
        val description = "Fails badly-framed HTTP requests"
        val filter = new ServerFilter
        def make(factory: ServiceFactory[Request, Response]) =
          filter.andThen(factory)
      }
  }

  /**
    * A filter that fails badly-framed responses
    */
  class ClientFilter extends SimpleFilter[Request, Response] {
    override def apply(request: Request, service: Service[Request, Response]
                      ): Future[Response] =
      service(request).flatMap { response =>
        val numHeaders = response.headerMap.getAll("Content-Length").toSet.size
        if (numHeaders > 1) {
          // if the response has multiple Content-Length headers with unique
          // values, it's invalid
          Future.exception(FramingException(
            s"""|Recieved $numHeaders conflicting `Content-Length` headers in
                | response from remote server""".stripMargin
          ))
        } else {
          // otherwise, it's fine
          Future.value(response)
        }
      }

  }

  object ClientFilter {

    val role = Stack.Role("ResponseFramingFilter")
    val module: Stackable[ServiceFactory[Request, Response]] =
      new Stack.Module0[ServiceFactory[Request, Response]] {
        val role = ClientFilter.role
        val description = "Fails badly-framed HTTP responses"
        val filter = new ClientFilter
        def make(factory: ServiceFactory[Request, Response]) =
          filter.andThen(factory)
      }
  }

  case class FramingException(reason: String) extends ProtocolException(reason)
}
