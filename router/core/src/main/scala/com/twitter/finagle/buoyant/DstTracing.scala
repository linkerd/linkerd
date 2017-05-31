package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.tracing.Trace
import io.buoyant.router.RouterLabel

object DstTracing {

  object Path {
    val role = Stack.Role("DstTracing.Path")

    def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
      new Stack.Module2[Dst.Path, RouterLabel.Param, ServiceFactory[Req, Rsp]] {
        val role = Path.role
        val description = "Traces unbound destination"
        def make(dst: Dst.Path, label: RouterLabel.Param, next: ServiceFactory[Req, Rsp]) =
          new Proxy(dst, label.label, next)
      }

    class Proxy[Req, Rsp](dst: Dst.Path, label: String, underlying: ServiceFactory[Req, Rsp])
      extends ServiceFactoryProxy(underlying) {
      private[this] val baseDtabShow = dst.baseDtab.show
      private[this] val localDtabShow = dst.localDtab.show
      private[this] val pathShow = dst.path.show
      override def apply(conn: ClientConnection) = {
        if (Trace.isActivelyTracing) {
          Trace.recordRpc(s"$label $pathShow")
          Trace.recordBinary("dtab.base", baseDtabShow)
          Trace.recordBinary("dtab.local", localDtabShow)
          Trace.recordBinary("service", pathShow)
        }
        self(conn)
      }
    }
  }

  object Bound {
    val role = Stack.Role("DstTracing.Bound")

    def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
      new Stack.Module1[Dst.Bound, ServiceFactory[Req, Rsp]] {
        val role = Bound.role
        val description = "Traces bound destination"
        def make(dst: Dst.Bound, next: ServiceFactory[Req, Rsp]) = new Proxy(dst, next)
      }

    class Proxy[Req, Rsp](dst: Dst.Bound, underlying: ServiceFactory[Req, Rsp])
      extends ServiceFactoryProxy(underlying) {
      private[this] val idStr = dst.idStr
      private[this] val path = dst.path.show
      override def apply(conn: ClientConnection) = {
        if (Trace.isActivelyTracing) {
          Trace.recordBinary("client", idStr)
          Trace.recordBinary("residual", path)
        }
        self(conn)
      }
    }
  }

}
