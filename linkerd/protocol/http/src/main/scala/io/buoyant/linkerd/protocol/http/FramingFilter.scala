package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{HeaderMap, Request, Response, Status}
import com.twitter.util.Future
import com.twitter.logging.Logger
import io.buoyant.linkerd.ProtocolException

object FramingFilter {
  // since the client and server filters go in separate stacks,
  // they can have the same role.
  val role = Stack.Role("FramingFilter")

  // this is factored out so that the same logic can
  // be applied to requests from clients and responses from services.
  private[FramingFilter] def headerErrors(headers: HeaderMap): Option[FramingException] =
    // if the length of the Content-Length key in the request/response's
    // header map is greater than 1, then there are duplicate values.
    if (headers.getAll("Content-Length").toSet.size > 1) {
      Some(FramingException("conflicting `Content-Length` headers"))
      // TODO: handle other bad framing error cases here as well
    } else None

  /**
   * A filter that fails badly-framed requests.
   */
  val serverFilter = new SimpleFilter[Request, Response] {
    // unlike the client-side filter, this needs its own logger, since
    // it responds to bad requests directly, rather than bubbling up
    // exceptions to an ErrorResponder
    private[this] val log = Logger.get("FramingFilter.ServerFIlter")

    override def apply(request: Request,
                       service: Service[Request, Response]): Future[Response] =
        headerErrors(request.headerMap)
          .map { case FramingException(reason) =>
            log.error(reason)
            Future.value(Headers.Err.respond(reason, Status.BadRequest))
          }
          .getOrElse(service(request))

  }


  /**
   * A filter that fails badly-framed responses
   */
  val clientFilter = new SimpleFilter[Request, Response] {

    override def apply(request: Request,
                       service: Service[Request, Response]): Future[Response] =
      service(request).flatMap { response =>
        headerErrors(response.headerMap)
          .map(Future.exception(_))
          .getOrElse(Future.value(response))
      }

  }

  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      override val role: Stack.Role = FramingFilter.role
      override val description = "Fails badly-framed HTTP requests"
      override def make(factory: ServiceFactory[Request, Response])
        : ServiceFactory[Request, Response] =
          serverFilter.andThen(factory)
    }

  val clientModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      override val role: Stack.Role = FramingFilter.role
      override val description = "Fails badly-framed HTTP responses"
      override def make(factory: ServiceFactory[Request, Response])
        : ServiceFactory[Request, Response] =
          clientFilter.andThen(factory)
    }

  case class FramingException(reason: String) extends ProtocolException(reason)
}
