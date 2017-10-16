package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{ServiceFactory, Stack}
import com.twitter.finagle.http.{Request, Response}
import io.buoyant.router.http.TimestampHeaderFilter

case class TimestampHeaderConfig(header: Option[String]) {
  @JsonIgnore
  def ++:(params: Stack.Params): Stack.Params =
    params + TimestampHeaderFilter.Param(header)

}

object TimestampHeaderConfig {

  case class Param(config: Option[TimestampHeaderConfig])
  implicit object Param extends Stack.Param[Param] {
    val default = Param(None)
  }

  /**
   * Configures parameters for `TimestampHeaderFilter`.
   */
  object module extends Stack.Module[ServiceFactory[Request, Response]] {
    override val role = Stack.Role("ConfigureTimestampHeaderFilter")
    override val description = TimestampHeaderFilter.module.description
    override val parameters = Seq(implicitly[Stack.Param[Param]])

    private type Stk = Stack[ServiceFactory[Request, Response]]

    override def make(params: Stack.Params, stk: Stk) = params[Param] match {
      case Param(None) => stk
      case Param(Some(config)) =>
        val mkNext: (Stack.Params, Stk) => Stk =
          (prms, next) => Stack.Leaf(this, next.make(config.++:(prms)))
        Stack.Node(this, mkNext, stk)
    }
  }
}