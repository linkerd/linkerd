package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Request, Response, Reset, LinkerdHeaders}
import com.twitter.logging.Logger
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory

/**
 * Coerces routing failures to the appropriate HTTP/2 error code
 * (i.e. REFUSED_STREAM).
 *
 * Additional failures are handled upstream (i.e. Netty4ServerDispatcher).
 */
class ErrorReseter extends SimpleFilter[Request, Response] {
  import ErrorReseter.{log, RefusedF}

  def apply(req: Request, service: Service[Request, Response]) =
    service(req).rescue(handler)

  private[this] val handler: PartialFunction[Throwable, Future[Response]] = {
    case e@RoutingFactory.UnknownDst(_, _) =>
      log.info(e, "unroutable request")
      RefusedF
  }
}

object ErrorReseter {
  private val log = Logger.get(getClass.getName)

  private val RefusedF = Future.exception(Reset.Refused)

  val filter = new ErrorReseter

  val role = Stack.Role("ErrorReseter")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = ErrorReseter.role
      val description = "Lifts router errors to stream resets"
      def make(factory: ServiceFactory[Request, Response]) = filter.andThen(factory)
    }
}
