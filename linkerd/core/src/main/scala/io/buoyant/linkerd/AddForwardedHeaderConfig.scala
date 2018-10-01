package io.buoyant.linkerd

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{ServiceFactory, Stack, Stackable}
import io.buoyant.router.ForwardedHeaderLabeler

case class AddForwardedHeaderConfig(
  by: Option[LabelerConfig],
  `for`: Option[LabelerConfig]
) {

  /** Appends AddForwardedHeader params to the given params. */
  @JsonIgnore
  def ++:(params: Stack.Params): Stack.Params =
    params + ForwardedHeaderLabeler.Enabled(true) + byParam(params) + forParam(params)

  @JsonIgnore
  private[this] def byParam(params: Stack.Params): ForwardedHeaderLabeler.By =
    by match {
      case None => ForwardedHeaderLabeler.By.default
      case Some(config) => ForwardedHeaderLabeler.By(config.mk(params))
    }

  @JsonIgnore
  private[this] def forParam(params: Stack.Params): ForwardedHeaderLabeler.For =
    `for` match {
      case None => ForwardedHeaderLabeler.For.default
      case Some(config) => ForwardedHeaderLabeler.For(config.mk(params))
    }
}

/**
 * Translates AddForwardedHeaderConfig.Param into AddForwardedHeader
 * configuration parameters.
 */
object AddForwardedHeaderConfig {

  case class Param(config: Option[AddForwardedHeaderConfig])
  implicit object Param extends Stack.Param[Param] {
    val default = Param(None)
  }

  /**
   * Configures parameters for `AddForwardedHeader`.
   *
   * Because `AddForwardedHeaderConfig` types may depend on stack
   * parameters (for instance, `Server.RouterLabel`)
   */
  def module[Req, Rep]: Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module[ServiceFactory[Req, Rep]] {
      val role = Stack.Role("ConfigureAddForwardedHeader")
      val description = "Adds params to configure an AddForwardedHeader module"
      val parameters = Seq(implicitly[Stack.Param[Param]])

      private type Stk = Stack[ServiceFactory[Req, Rep]]

      def make(params: Stack.Params, stk: Stk) = params[Param] match {
        case Param(None) => stk
        case Param(Some(config)) =>
          // Wrap the underlying stack, applying the ForwardedHeaderConfig
          val mkNext: (Stack.Params, Stk) => Stk =
            (prms, next) => Stack.leaf(this, next.make(prms ++: config))
          Stack.node(this, mkNext, stk)
      }
    }

}
