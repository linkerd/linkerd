package io.buoyant.linkerd.protocol.http

import com.twitter.finagle._
import com.twitter.finagle.buoyant.linkerd._
import com.twitter.finagle.http.{Request, Response, Status, param}
import com.twitter.finagle.naming.buoyant.{RichConnectionFailedExceptionWithPath, RichNoBrokersAvailableException}
import com.twitter.finagle.service.RetryPolicy.RetryableWriteException
import com.twitter.logging.{Level, Logger}
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.ResponseException
import io.buoyant.router.http.MaxCallDepthFilter
import scala.util.control.NonFatal

class ErrorResponder(maxHeaderSize: Int, maxErrResponseSize: Int)
  extends SimpleFilter[Request, Response] {
  private[this] val log = Logger.get("ErrorResponseFilter")

  def apply(req: Request, service: Service[Request, Response]) =
    service(req).handle(handler)

  private[this] val handler: PartialFunction[Throwable, Response] = {
    case NonFatal(e) =>
      e match {
        case RoutingFactory.UnknownDst(_, _) =>
          log.debug(e, "unknown dst")
          Headers.Err.respond(e.getMessage, Status.BadRequest, maxHeaderSize, maxErrResponseSize)
        case ErrorResponder.HttpResponseException(rsp) =>
          rsp
        case e: RichNoBrokersAvailableException =>
          Headers.Err.respond(e.exceptionMessage(), Status.BadGateway, maxHeaderSize, maxErrResponseSize)
        case e: RichConnectionFailedExceptionWithPath =>
          Headers.Err.respond(e.exceptionMessage, Status.BadGateway, maxHeaderSize, maxErrResponseSize)
        case e: MaxCallDepthFilter.MaxCallDepthExceeded =>
          Headers.Err.respond(e.getMessage, Status.BadRequest, maxHeaderSize, maxErrResponseSize)
        case _ =>
          val message = e.getMessage match {
            case null => e.getClass.getName
            case msg => msg
          }
          val status = e match {
            case _: TimeoutException | Failure(Some(_: TimeoutException)) =>
              Status.ServiceUnavailable
            case _ =>
              log.error("service failure: %s", e)
              Status.BadGateway
          }
          val rsp = Headers.Err.respond(message, status, maxHeaderSize, maxErrResponseSize)
          if (RetryableWriteException.unapply(e).isDefined) {
            Headers.Retryable.set(rsp.headerMap, retryable = true)
          }
          rsp
      }
  }
}

object ErrorResponder {
  val role = Stack.Role("ErrorResponder")
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module2[param.MaxHeaderSize, Headers.param.MaxErrResponseSize, ServiceFactory[Request, Response]] {
      val role = ErrorResponder.role
      val description = "Crafts HTTP responses for routing errors"
      def filter(maxHeaderSize: Int, maxErrResponseSize: Int) =
        new ErrorResponder(maxHeaderSize, maxErrResponseSize)
      def make(maxHeaderSize: param.MaxHeaderSize, maxErrResponseSize: Headers.param.MaxErrResponseSize,
        factory: ServiceFactory[Request, Response]) =
        filter(maxHeaderSize.size.bytes.toInt, maxErrResponseSize.size.bytes.toInt).andThen(factory)
    }

  case class HttpResponseException(rsp: Response) extends ResponseException {
    val logLevel = Level.TRACE
  }
}
