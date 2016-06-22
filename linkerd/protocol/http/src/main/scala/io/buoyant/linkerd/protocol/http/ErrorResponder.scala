package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.linkerd._
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.logging.Logger
import com.twitter.util.NonFatal
import io.buoyant.router.RoutingFactory
import java.net.URLEncoder

class ErrorResponder extends SimpleFilter[Request, Response] {
  private[this] val log = Logger.get("ErrorResponseFilter")

  def apply(req: Request, service: Service[Request, Response]) =
    service(req).handle(handler)

  private[this] val handler: PartialFunction[Throwable, Response] = {
    case NonFatal(e) =>
      val status = e match {
        case e@RoutingFactory.UnknownDst(_, _) =>
          log.debug(e, "unknown dst")
          Status.BadRequest
        case _ =>
          log.error(e, "service failure")
          Status.BadGateway
      }
      val message = e.getMessage match {
        case null => e.getClass.getName
        case msg => msg
      }
      Headers.Err.respond(message, status)
  }
}

object ErrorResponder {
  val role = Stack.Role("ErrorResponder")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = ErrorResponder.role
      val description = "Crafts HTTP responses for routing errors"
      val filter = new ErrorResponder
      def make(factory: ServiceFactory[Request, Response]) =
        filter.andThen(factory)
    }
}
