package io.buoyant.router

import com.twitter.finagle.Stack

/**
 * RouterLabel.Param is a string stack param containing the router's label.
 *
 * @see com.twitter.finagle.Stack.Param
 */
object RouterLabel {
  case class Param(label: String) {
    def mk(): (Param, Stack.Param[Param]) =
      (this, Param.param)
  }

  object Param {
    implicit val param: Stack.Param[Param] =
      Stack.Param(Param(""))
  }
}
