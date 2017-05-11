package io.buoyant.router.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Message, Request, Response}

/**
 * Appends the `via` header to all requests and responses.
 * See https://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-14#section-9.9
 */
object ViaHeaderFilter {
  val Key = "via"

  case class Param(via: String)
  implicit object Param extends Stack.Param[Param] {
    val default = Param("h2 linkerd")
  }

  /**
   * Appends the 'via' header.
   */
  class Filter(via: String) extends SimpleFilter[Request, Response] {
    def apply(req: Request, svc: Service[Request, Response]) =
      svc(setRequest(req)).map(setResponse)

    private[this] def setMessage[T <: Message]: T => T = { msg =>
      val updated = msg.headers.getAll(Key) match {
        case Nil => via
        case vals => vals.mkString("", ", ", s", $via")
      }
      msg.headers.set(Key, updated); ()
      msg
    }
    private[this] val setRequest = setMessage[Request]
    private[this] val setResponse = setMessage[Response]
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module1[Param, ServiceFactory[Request, Response]] {
      val role = Stack.Role("ViaHeaderFilter")
      val description = "Appends the Via header to the request and response."
      def make(param: Param, next: ServiceFactory[Request, Response]) =
        new Filter(param.via).andThen(next)
    }
}
