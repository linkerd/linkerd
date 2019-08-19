package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{LinkerdHeaders, Request, Reset, Response, Status, Stream}
import com.twitter.finagle.naming.buoyant.{RichConnectionFailedExceptionWithPath, RichNoBrokersAvailableException}
import com.twitter.finagle.{Status => _, _}
import com.twitter.logging.{Level, Logger}
import com.twitter.util.Future
import io.buoyant.linkerd.protocol.h2.ErrorReseter.H2ResponseException
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.ResponseException
import io.buoyant.router.http.MaxCallDepthFilter.MaxCallDepthExceeded

/**
 * Coerces routing failures to the appropriate HTTP/2 error code
 * (i.e. REFUSED_STREAM).
 *
 * Additional failures are handled upstream (i.e. Netty4ServerDispatcher).
 */
class ErrorReseter extends SimpleFilter[Request, Response] {
  import ErrorReseter.{RefusedF, log}

  def apply(req: Request, service: Service[Request, Response]) =
    service(req).rescue(handler)

  private[this] val handler: PartialFunction[Throwable, Future[Response]] = {
    case e@RoutingFactory.UnknownDst(req, reason) =>
      log.info("unroutable request: %s: %s", req, reason)
      RefusedF
    case e: RichNoBrokersAvailableException =>
      Future.value(LinkerdHeaders.Err.respond(e.exceptionMessage(), Status.BadGateway))
    case e: RichConnectionFailedExceptionWithPath =>
      Future.value(LinkerdHeaders.Err.respond(e.exceptionMessage, Status.BadGateway))
    case e: MaxCallDepthExceeded =>
      Future.value(LinkerdHeaders.Err.respond(e.getMessage, Status.BadRequest))
    case H2ResponseException(rsp) =>
      Future.value(rsp)
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

  case class H2ResponseException(rsp: Response) extends ResponseException {
    val logLevel = Level.TRACE
  }
}
