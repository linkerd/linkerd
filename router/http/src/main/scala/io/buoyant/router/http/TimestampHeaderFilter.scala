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

  case class Header(header: String) {
    def mk(): (Header, Stack.Param[Header]) = (this, Header.param)
  }

  object Header {
    implicit val param = Stack.Param(Header("X-Request-Start"))
  }

  val role = Stack.Role("TimestampHeaderFilter")

  object module extends Stack.Module1[
    TimestampHeaderFilter.Header,
    ServiceFactory[Request, Response]
  ] {
    override val role: Stack.Role = TimestampHeaderFilter.role
    override val description = "Adds a timestamp header to requests"
    override def make(
      param: TimestampHeaderFilter.Header,
      next: ServiceFactory[Request, Response]
    ): ServiceFactory[Request, Response] = {
      val Header(header) = param
      filter(header).andThen(next)
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
