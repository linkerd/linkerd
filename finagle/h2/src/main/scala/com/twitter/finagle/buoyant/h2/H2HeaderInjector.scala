package com.twitter.finagle.buoyant.h2

import com.twitter.finagle
import com.twitter.finagle._
import com.twitter.util.Future

object H2HeaderInjector {
  def module(headerMap: Map[String, String]): Stackable[ServiceFactory[Request, Response]] = {
    new finagle.Stack.Module0[ServiceFactory[Request, Response]] {
      override def make(next: ServiceFactory[Request, Response]): ServiceFactory[Request, Response] =
        new HeaderInjector(headerMap).andThen(next)

      override def role: Stack.Role = Stack.Role("H2HeaderInjector")
      override def description: String = "Add arbitrary headers to H2 requests"
    }
  }
}

class HeaderInjector(injectHeaders: Map[String, String]) extends SimpleFilter[Request, Response] {
  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    val req = request.dup()
    injectHeaders.foreach { kvPair =>
      req.headers.add(kvPair._1, kvPair._2); ()
    }
    service(req)
  }
}
