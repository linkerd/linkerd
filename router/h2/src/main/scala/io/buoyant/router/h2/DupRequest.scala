package io.buoyant.router.h2

import com.twitter.finagle.{Service, ServiceFactory, SimpleFilter, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Request, Response}

object DupRequest {
  val role = Stack.Role("DupRequest")

  object filter extends SimpleFilter[Request, Response] {
    def apply(req: Request, service: Service[Request, Response]) = service(req.dup())
  }

  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module0[ServiceFactory[Request, Response]] {
      val role = DupRequest.role
      val description = "Provides the rest of the subsequent stack with a duplicate request"
      def make(next: ServiceFactory[Request, Response]) = filter.andThen(next)
    }
}
