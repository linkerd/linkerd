package io.buoyant.router

import com.twitter.finagle.{Path, Service, ServiceFactory, SimpleFilter, Stack}
import com.twitter.finagle.buoyant.{Dst, EncodeResidual}
import com.twitter.finagle.mux.{Request, Response}
import com.twitter.util._

object MuxEncodeResidual extends Stack.Module1[Dst.Bound, ServiceFactory[Request, Response]] {
  val role = EncodeResidual.role
  val description = EncodeResidual.description
  def make(bound: Dst.Bound, factory: ServiceFactory[Request, Response]) =
    new ResidualFilter(bound.path) andThen factory

  class ResidualFilter(path: Path) extends SimpleFilter[Request, Response] {
    def apply(req: Request, service: Service[Request, Response]) =
      service(Request(path, req.body))
  }
}
