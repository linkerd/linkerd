package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.http.Fields.Via
import com.twitter.finagle.http.{Request, Response}
import com.twitter.util.Future
import io.buoyant.router.http.ForwardClientCertFilter.Enabled
import io.buoyant.router.http.MaxCallDepthFilter.MaxCallDepthExceeded
import scala.util.control.NoStackTrace

class MaxCallDepthFilter[Req, H: HeadersLike, Rep](maxCalls: Int, headerKey: String)
  (implicit requestLike: RequestLike[Req, H]) extends SimpleFilter[Req, Rep] {

  def numCalls(viaValue: String) = viaValue.split(",").length

  def apply(req: Req, svc: Service[Req, Rep]): Future[Rep] = {
    val headersLike = implicitly[HeadersLike[H]]

    headersLike.get(requestLike.headers(req), headerKey) match {
      case Some(v) if numCalls(v) > maxCalls => Future.exception(MaxCallDepthExceeded(maxCalls))
      case _ => svc(req)
    }
  }
}

object MaxCallDepthFilter {

  final case class MaxCallDepthExceeded(calls: Int)
    extends Exception(s"Maximum number of calls ($calls) has been exceeded. Please check for proxy loops.")
      with NoStackTrace

  final case class Param(value: Int) extends AnyVal {
    def mk(): (Param, Stack.Param[Param]) = (this, Param.param)
  }

  object Param {
    implicit val param = Stack.Param(Param(1000))
  }

  def module[Req, H: HeadersLike, Rep](headerKey: String)
    (implicit requestLike: RequestLike[Req, H]): Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module1[Param, ServiceFactory[Req, Rep]] {
      val role = Stack.Role("MaxCallDepthFilter")
      val description = "Limits the number of hops by looking at the Via header of a request"

      def make(param: Param, next: ServiceFactory[Req, Rep]) =
        new MaxCallDepthFilter(param.value, headerKey) andThen next

    }
}
