package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.{Future, Time}

/**
 * Adds a timestamp header to a message.
 *
 * This is intended to be used for the New Relic request queue's
 * `x-request-start` header, but might be useful for other purposes?
 */
object TimestampHeaderFilter {

  case class Param(header: Option[String]) {
    def mk(): (Param, Stack.Param[Param]) = (this, Param.param)
  }

  object Param {
    implicit val param = Stack.Param(Param(None))
  }

  val role = Stack.Role("TimestampHeaderFilter")

  object module extends Stack.Module1[Param, ServiceFactory[Request, Response]] {
    override val role: Stack.Role = TimestampHeaderFilter.role
    override val description = "Adds a timestamp header to requests"
    override def make(
      param: Param,
      next: ServiceFactory[Request, Response]
    ): ServiceFactory[Request, Response] = {
      param match {
        case Param(None) => next
        case Param(Some(header)) => filter(header).andThen(next)
      }

    }
  }

  def filter(header: String): Filter[Request, Response, Request, Response] =
    new SimpleFilter[Request, Response] {
      override def apply(
        req: Request,
        svc: Service[Request, Response]
      ): Future[Response] = {
        req.headerMap.add(header, Time.now.inMillis.toString)
        svc(req)
      }

    }

}
