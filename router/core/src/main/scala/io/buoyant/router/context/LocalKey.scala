package io.buoyant.router.context

import com.twitter.finagle.{SimpleFilter, Service, ServiceFactory, Stack, Stackable}
import com.twitter.finagle.context.Contexts

object LocalKey {

  private[this] class SetterFilter[T, Req, Rsp](key: Contexts.local.Key[T], value: T)
    extends SimpleFilter[Req, Rsp] {
    def apply(req: Req, service: Service[Req, Rsp]) =
      Contexts.local.let(key, value) { service(req) }
  }

  private class SetterModule[P: Stack.Param, Req, Rsp](
    key: Contexts.local.Key[P],
    override val role: Stack.Role,
    override val description: String
  ) extends Stack.Module1[P, ServiceFactory[Req, Rsp]] {

    override def make(param: P, next: ServiceFactory[Req, Rsp]): ServiceFactory[Req, Rsp] = {
      val filter = new SetterFilter[P, Req, Rsp](key, param)
      filter.andThen(next)
    }
  }
}

private[context] class LocalKey[P: Stack.Param](name: String) extends Contexts.local.Key[P] {
  def current: Option[P] = Contexts.local.get(this)

  object Setter {
    val role = Stack.Role(s"$name.Setter")
    val description = s"Sets $name on the local context"
    def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
      new LocalKey.SetterModule[P, Req, Rsp](LocalKey.this, role, description)
  }
}
