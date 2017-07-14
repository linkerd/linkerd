package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.buoyant.linkerd.Headers
import com.twitter.finagle.http.{Status, _}
import com.twitter.util.{Future, Return, Throw}
import com.twitter.logging.Logger
import io.buoyant.linkerd.ProtocolException

object FramingFilter {
  // since the client and server filters go in separate stacks,
  // they can have the same role.
  val role = Stack.Role("FramingFilter")

  // this is factored out so that the same logic can
  // be applied to requests from clients and responses from services.
  private[FramingFilter] def filterMessage[M <: Message](message: M): Future[M] = {
    val headers = message.headerMap
    val contentLengths = message.headerMap.getAll("Content-Length")
    val contentLengthEx = FramingException("conflicting `Content-Length` headers")

    if (contentLengths.toSet.size > 1) {
      // if the length of the Content-Length key in the request/response's
      // header map is greater than 1, then there are duplicate values.
      Future.exception(contentLengthEx)
    } else {
      if (contentLengths.nonEmpty &&
        headers.get("Transfer-Encoding").contains("chunked")) {
        // if the message contains both a `Content-Length` header and
        // `Transfer-Encoding: chunked`, remove the `Content-Length` header
        headers.remove("Content-Length")
        // this *should* modify the headers in-place? i dislike mutability...
      }
      Future.value(message)
    }

  }

  /**
   * A filter that fails badly-framed requests.
   */
  val clientFilter = new SimpleFilter[Request, Response] {
    // unlike the server filter, this needs its own logger, since
    // it responds to bad requests directly, rather than bubbling up
    // exceptions to an ErrorResponder
    private[this] val log = Logger.get("FramingFilter.ServerFilter")

    override def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] =
      filterMessage(request)
        .transform {
          case Throw(e: FramingException) =>
            // if the exception was a FramingException, turn the Future into
            // a success by responding with Bad Request
            log.error("framing error in request", e)
            Future.value(Headers.Err.respond(e.reason, Status.BadRequest))
          case Throw(e) =>
            // other exceptions should be propagated
            Future.exception(e)
          case Return(request) => service(request)
        }
  }

  /**
   * A filter that fails badly-framed responses
   */
  val serverFilter = new SimpleFilter[Request, Response] {

    override def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] =
      service(request).flatMap(filterMessage)

  }

  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      override val role: Stack.Role = FramingFilter.role
      override val description = "Fails badly-framed HTTP requests"
      override def make(factory: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] =
        serverFilter.andThen(factory)
    }

  val clientModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      override val role: Stack.Role = FramingFilter.role
      override val description = "Fails badly-framed HTTP responses"
      override def make(factory: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] =
        clientFilter.andThen(factory)
    }

  case class FramingException(reason: String) extends ProtocolException(reason)
}
