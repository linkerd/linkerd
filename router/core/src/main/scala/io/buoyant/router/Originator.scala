package io.buoyant.router

import com.twitter.finagle.Stack

/**
 * Originator.Param is a boolean stack param that is used to configure a
 * [[io.buoyant.router.Router Router]]. If the param is set to true, it
 * indicates that the router is the first hop in a linker-to-linker request,
 * and the router's stats are updated to reflect that.
 *
 * @see com.twitter.finagle.Stack.Param
 */
object Originator {
  case class Param(originator: Boolean) {
    def mk(): (Param, Stack.Param[Param]) =
      (this, Param.param)
  }

  object Param {
    implicit val param: Stack.Param[Param] =
      Stack.Param(Param(false))
  }
}
