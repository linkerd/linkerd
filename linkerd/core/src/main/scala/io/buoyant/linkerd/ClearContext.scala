package io.buoyant.linkerd

import com.twitter.finagle._
import com.twitter.finagle.context.Contexts
import com.twitter.finagle.tracing.Trace

/** Clears local and broadcast contexts from the request */
object ClearContext {
  val role = Stack.Role("ClearContext")
  val description = "May clear local and broadcast contexts from inbound requests"

  case class Param(enabled: Boolean)
  implicit object Param extends Stack.Param[Param] {
    val default = Param(false)
  }

  def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module1[Param, ServiceFactory[Req, Rsp]] {
      val role = ClearContext.role
      val description = ClearContext.description
      def make(_param: Param, next: ServiceFactory[Req, Rsp]) = {
        if (_param.enabled) filter.andThen(next)
        else next
      }
    }

  def filter[Req, Rsp]: Filter[Req, Rsp, Req, Rsp] =
    new SimpleFilter[Req, Rsp] {
      def apply(req: Req, svc: Service[Req, Rsp]) =
        Contexts.local.letClear() {
          Contexts.broadcast.letClear() {
            Dtab.unwind {
              Dtab.local = Dtab.empty
              Trace.letClear {
                svc(req)
              }
            }
          }
        }
    }
}
