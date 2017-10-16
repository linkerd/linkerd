package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Time

/**
 * Adds a timestamp header to a message.
 *
 * This is intended to be used for the New Relic request queue's
 * `x-request-start` header, but might be useful for other purposes?
 */
object TimestampHeaderFilter {

  val DefaultHeader: String = "X-Request-Start"

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
    ): ServiceFactory[Request, Response] = param match {
      case Param(Some(header)) => filter(header) andThen next
      case Param(None) => next
    }
  }

  def filter(header: String): Filter[Request, Response, Request, Response] =
    (req: Request, svc: Service[Request, Response]) => {
      val reqT = Time.now
      svc(req).map { rsp =>
        rsp.headerMap += header -> reqT.inMillis.toString
        rsp
      }
    }

}
