package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.{ServiceFactory, Stack, Stackable}
import com.twitter.finagle.buoyant.h2.{Request, Response}
import io.buoyant.router.http.{AddForwardedHeader, LabelerConfig}

case class H2AddForwardedHeaderConfig(
  by: Option[LabelerConfig],
  `for`: Option[LabelerConfig]
) {

  /** Appends AddForwardedHeader params to the given params. */
  @JsonIgnore
  def ++:(params: Stack.Params): Stack.Params =
    params + AddForwardedHeader.H2.Enabled(true) + byParam(params) + forParam(params)

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
object H2AddForwardedHeaderConfig {

  case class Param(config: Option[H2AddForwardedHeaderConfig])
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
      val role = Stack.Role("ConfigureH2AddForwardedHeader")
      val description = AddForwardedHeader.H2.module.description
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
