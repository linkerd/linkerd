package io.buoyant.router

import com.twitter.finagle.{ClientConnection, Service, ServiceFactory}
import com.twitter.util.{Future, Time}
import io.buoyant.router.DiscardingFactoryToService.RequestDiscarder
import io.buoyant.test.FunSuite
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NoStackTrace

class DiscardingFactoryToServiceTest  extends FunSuite {
  case class Request(discarded: AtomicBoolean = new AtomicBoolean(false))
  case class Response()


  private def serviceFactory(process: ClientConnection => Future[Service[Request, Response]]) = new ServiceFactory[Request, Response] {
    override def apply(conn: ClientConnection): Future[Service[Request, Response]] = process(conn)
    override def close(deadline: Time): Future[Unit] = Future.Unit
  }

  final case object ServiceFactoryException extends NoStackTrace

  test("discards request on service factory failure") {
    val discarder = RequestDiscarder[Request](_.discarded.set(true))
    val failingSrvFactory = serviceFactory(_ => Future.exception(ServiceFactoryException))
    val factToService = new DiscardingFactoryToService(discarder, failingSrvFactory)
    val request = Request()

    val p = factToService(request)
    intercept[ServiceFactoryException.type ] { await(p) }
    assert(request.discarded.get())
  }

  test("does not discard request on service factory success") {
    val discarder = RequestDiscarder[Request](_.discarded.set(true))
    val failingSrvFactory = serviceFactory(_ => Future.value(Service.const(Future.value(Response()))))
    val factToService = new DiscardingFactoryToService(discarder, failingSrvFactory)
    val request = Request()

    val p = factToService(request)
    await(p) == Response()
    assert(!request.discarded.get())
  }






}
