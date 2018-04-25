package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.http.{Request, Response}
import com.twitter.finagle.{ServiceFactory, Stack, Stackable}
import io.buoyant.router.http.{AddForwardedHeader, LabelerConfig}

case class AddForwardedHeaderConfig(
  by: Option[LabelerConfig],
  `for`: Option[LabelerConfig]
) {

  /** Appends AddForwardedHeader params to the given params. */
  @JsonIgnore
  def ++:(params: Stack.Params): Stack.Params =
    params + AddForwardedHeader.H1.Enabled(true) + byParam(params) + forParam(params)

  @JsonIgnore
  private[this] def byParam(params: Stack.Params): AddForwardedHeader.Labeler.By =
    by match {
      case None => AddForwardedHeader.Labeler.By.default
      case Some(config) => AddForwardedHeader.Labeler.By(config.mk(params))
    }

  @JsonIgnore
  private[this] def forParam(params: Stack.Params): AddForwardedHeader.Labeler.For =
    `for` match {
      case None => AddForwardedHeader.Labeler.For.default
      case Some(config) => AddForwardedHeader.Labeler.For(config.mk(params))
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
  val module: Stackable[ServiceFactory[Request, Response]] =
    new Stack.Module[ServiceFactory[Request, Response]] {
      val role = Stack.Role("ConfigureAddForwardedHeader")
      val description = AddForwardedHeader.H1.module.description
      val parameters = Seq(implicitly[Stack.Param[Param]])

      private type Stk = Stack[ServiceFactory[Request, Response]]

      def make(params: Stack.Params, stk: Stk) = params[Param] match {
        case Param(None) => stk
        case Param(Some(config)) =>
          // Wrap the underlying stack, applying the ForwardedHeaderConfig
          val mkNext: (Stack.Params, Stk) => Stk =
            (prms, next) => Stack.Leaf(this, next.make(prms ++: config))
          Stack.Node(this, mkNext, stk)
      }
    }

}
