package com.twitter.finagle.buoyant

import com.twitter.finagle._

object EncodeResidual {
  val role = Stack.Role("EncodeResidual")
  val description = "Supports rewriting of downstream requests"

  trait Module[Req, Rsp] extends Stack.Module1[Dst.Bound, ServiceFactory[Req, Rsp]] {
    val role = EncodeResidual.role
    val description = EncodeResidual.description

    final def make(dst: Dst.Bound, next: ServiceFactory[Req, Rsp]) =
      mkFilter(dst).andThen(next)

    def mkFilter(dst: Dst.Bound): Filter[Req, Rsp, Req, Rsp]
  }
}
