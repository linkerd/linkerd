package io.buoyant.router.http

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.http.{MediaType, Request, Response, Status}
import com.twitter.logging.Logger
import com.twitter.util.NonFatal
import io.buoyant.router.RoutingFactory

object ErrorResponder extends Stack.Module0[ServiceFactory[Request, Response]] {
  val role = Stack.Role("ErrorResponder")
  val description = "Crafts HTTP responses for routing errors"
  def make(factory: ServiceFactory[Request, Response]) = filter andThen factory

  object filter extends SimpleFilter[Request, Response] {
    private[this] val log = Logger.get("ErrorResponseFilter")

    def apply(req: Request, service: Service[Request, Response]) =
      service(req).handle(handler)

    private[this] val handler: PartialFunction[Throwable, Response] = {
      case NonFatal(e) =>
        val status = e match {
          case e@RoutingFactory.UnknownDst(_, _) =>
            log.debug("%s", e.getMessage)
            Status.BadRequest
          case _ =>
            log.error(e, "service failure")
            Status.BadGateway
        }

        val rsp = Response(status)
        rsp.headerMap(Headers.Err) = e.getMessage
        rsp.contentType = MediaType.Txt
        rsp.contentString = e.getMessage
        rsp
    }
  }
}
