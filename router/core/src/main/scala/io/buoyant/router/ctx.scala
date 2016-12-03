package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.context.Contexts
import com.twitter.util.Local

package object ctx {

  private class SetFilter[T, Req, Rsp](key: Contexts.local.Key[T], value: T)
    extends SimpleFilter[Req, Rsp] {

    def apply(req: Req, service: Service[Req, Rsp]) = {
      val save = Local.save()
      try Contexts.local.let(key, value) { service(req) }
      finally Local.restore(save)
    }
  }

  private class SetModule[P: Stack.Param, Req, Rsp](
    key: Contexts.local.Key[P],
    override val role: Stack.Role,
    override val description: String
  ) extends Stack.Module1[P, ServiceFactory[Req, Rsp]] {

    override def make(param: P, next: ServiceFactory[Req, Rsp]): ServiceFactory[Req, Rsp] = {
      val filter = new SetFilter[P, Req, Rsp](key, param)
      filter.andThen(next)
    }
  }

  object DstPath extends Contexts.local.Key[Dst.Path] {

    def current: Option[Dst.Path] = Contexts.local.get(this)

    object Setter {
      val role = Stack.Role("DstPath.Setter")
      val description = "Sets Dst.Path on the local context"
      def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
        new SetModule[Dst.Path, Req, Rsp](DstPath.this, role, description)
    }
  }

  object DstBound extends Contexts.local.Key[Dst.Bound] {

    def current: Option[Dst.Bound] = Contexts.local.get(this)

    object Setter {
      val role = Stack.Role("DstBound.Setter")
      val description = "Sets Dst.Bound on the local context"
      def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
        new SetModule[Dst.Bound, Req, Rsp](DstBound.this, role, description)
    }
  }
}
