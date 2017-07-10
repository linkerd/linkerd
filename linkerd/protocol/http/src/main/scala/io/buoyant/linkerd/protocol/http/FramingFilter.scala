package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.http.{HeaderMap, Request, Response, Status}
import com.twitter.logging.Logger
import com.twitter.util.Future
import io.buoyant.linkerd.ProtocolException

object FramingFilter {
  // this is factored out so that the same logic can
  // be applied to requests from clients and responses from services.
  def headerErrors(headers: HeaderMap): Option[FramingException] =
    // if the length of the Content-Length key in the request/response's
    // header map is greater than 1, then there are duplicate values.
    if (headers.getAll("Content-Length").toSet.size > 1) {
      Some(FramingException("conflicting `Content-Length` headers"))
    } else None

  /**
   * A filter that fails badly-framed requests.
   */
  class ServerFilter extends SimpleFilter[Request, Response] {
    private[this] val log = Logger.get("FramingFilter.ServerFilter")

    override def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] =
      headerErrors(request.headerMap).map(Future.exception(_))
        .getOrElse(service(request))

  }

  object ServerFilter {
    val role = Stack.Role("ServerFramingFilter")
  }

  /**
   * A filter that fails badly-framed responses
   */
  class ClientFilter extends SimpleFilter[Request, Response] {

    override def apply(
      request: Request,
      service: Service[Request, Response]
    ): Future[Response] =
      service(request).flatMap { response =>
        headerErrors(response.headerMap)
          .map(Future.exception(_))
          .getOrElse(Future.value(response))
      }

  }

  object ClientFilter {
    val role = Stack.Role("ClientFramingFilter")
  }

  val serverModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = ServerFilter.role
      val description = "Fails badly-framed HTTP requests"
      val filter = new ServerFilter
      def make(factory: ServiceFactory[Request, Response]) =
        filter.andThen(factory)
    }

  val clientModule: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = ClientFilter.role
      val description = "Fails badly-framed HTTP responses"
      val filter = new ClientFilter
      def make(factory: ServiceFactory[Request, Response]) =
        filter.andThen(factory)
    }

  case class FramingException(reason: String) extends ProtocolException(reason)
}
