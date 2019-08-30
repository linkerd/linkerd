package io.buoyant.router

import com.twitter.finagle._
import com.twitter.util.{Future, Time}
import io.buoyant.router.DiscardingFactoryToService.RequestDiscarder

/**
 * Turns a com.twitter.finagle.ServiceFactory into a
 * com.twitter.finagle.Service which acquires a new service for
 * each request. Additionally uses a RequestDiscarder to discard
 * the request and release any resources associated with it.
 *
 * NB: This code was copied from com.twitter.finagle.FactoryToService
 * but modified to be able to discard the request on failure.
 */
class DiscardingFactoryToService[Req, Rep](requestDiscarder: RequestDiscarder[Req], factory: ServiceFactory[Req, Rep]) extends Service[Req, Rep] {

  def apply(request: Req): Future[Rep] =
    factory().onFailure(_ => requestDiscarder.discard(request)).flatMap { service =>
      service(request).ensure {
        service.close()
        ()
      }
    }

  override def close(deadline: Time): Future[Unit] = factory.close(deadline)
  override def status: Status = factory.status
}

object DiscardingFactoryToService {
  val role = Stack.Role("DiscardingFactoryToService")

  case class Enabled(enabled: Boolean) {
    def mk(): (Enabled, Stack.Param[Enabled]) =
      (this, Enabled.param)
  }
  object Enabled {
    implicit val param = Stack.Param(Enabled(false))
  }

  case class RequestDiscarder[Req](discard: Req => Unit)
  object RequestDiscarder {
    private val _param = new Stack.Param[RequestDiscarder[Any]] {
      val default = RequestDiscarder(_ => ()) // default is noop
    }
    def param[Req] = _param.asInstanceOf[Stack.Param[RequestDiscarder[Req]]]
  }
}
