package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Request, Response, Status, LinkerdHeaders}
import com.twitter.logging.Logger
import com.twitter.util.NonFatal
import io.buoyant.router.RoutingFactory
import java.net.URLEncoder

class ErrorResponder extends SimpleFilter[Request, Response] {
  private[this] val log = ErrorResponder.log

  def apply(req: Request, service: Service[Request, Response]) =
    service(req).handle(handler)

  private[this] val handler: PartialFunction[Throwable, Response] = {
    case NonFatal(e) =>
      val status = e match {
        case e@RoutingFactory.UnknownDst(_, _) =>
          log.debug(e, "unknown dst")
          Status.BadRequest
        case e =>
          log.error(e, "service failure")
          Status.BadGateway
      }
      val msg = e.getMessage match {
        case null => e.getClass.getName
        case msg => msg
      }
      LinkerdHeaders.Err.respond(msg, status)
  }
}

object ErrorResponder {
  private val log = Logger.get("ErrorResponder")

  val filter = new ErrorResponder

  val role = Stack.Role("ErrorResponder")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = ErrorResponder.role
      val description = "Crafts H2 responses for routing errors"
      def make(factory: ServiceFactory[Request, Response]) = filter.andThen(factory)
    }
}
