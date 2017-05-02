package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.util.StackRegistry
import com.twitter.util.{Closable, Future, Time}

object PathRegistry extends StackRegistry {
  val registryName: String = "path"

  val role = Stack.Role("PathRegistryLifecycle")

  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] = new Stack.Module[ServiceFactory[Req, Rep]] {
    val role: Stack.Role = PathRegistry.role

    val description: String = "Maintains the PathRegistry for the stack"
    def parameters: Seq[Stack.Param[_]] = Seq(
      implicitly[Stack.Param[Dst.Path]]
    )

    def make(
      params: Stack.Params,
      next: Stack[ServiceFactory[Req, Rep]]
    ): Stack[ServiceFactory[Req, Rep]] = {
      val Dst.Path(path, dtabBase, dtabLocal) = params[Dst.Path]

      val shown = path.show
      PathRegistry.register(path.show, next, params)

      CanStackFrom.fromFun[ServiceFactory[Req, Rep]].toStackable(role, { factory: ServiceFactory[Req, Rep] =>
        new ServiceFactoryProxy[Req, Rep](factory) {
          override def close(deadline: Time): Future[Unit] = {
            PathRegistry.unregister(shown, next, params)
            self.close(deadline)
          }
        }
      }) +: next
    }
  }
}
