package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.h2.{Request, Response}
import com.twitter.finagle._
import com.twitter.finagle.client.Transporter.EndpointAddr
import com.twitter.finagle.naming.buoyant.DstBindingFactory
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory.BaseDtab
import io.buoyant.router.{RouterLabel, RoutingFactory}

class H2RequestActiveTracer(
  endpoint: EndpointAddr,
  namers: DstBindingFactory.Namer,
  dtab: BaseDtab,
  routerLabel: String
) extends SimpleFilter[Request, Response] {
  override def apply(
    request: Request,
    service: Service[Request, Response]
  ): Future[Response] = ???
}

object H2RequestActiveTracer {
  val module: Stackable[ServiceFactory[Request, Response]] = {
    new Stack.Module4[EndpointAddr, RoutingFactory.BaseDtab, DstBindingFactory.Namer, RouterLabel.Param, ServiceFactory[Request, Response]] {

      override def role: Stack.Role = Stack.Role("H2RequestEvaluator")

      override def description: String = "Intercepts to respond with useful client destination info"

      override def make(
        endpoint: EndpointAddr,
        dtab: BaseDtab,
        interpreter: DstBindingFactory.Namer,
        label: RouterLabel.Param,
        next: ServiceFactory[Request, Response]
      ): ServiceFactory[Request, Response] =
        new H2RequestActiveTracer(endpoint, interpreter, dtab, label.label).andThen(next)
    }
  }
}
