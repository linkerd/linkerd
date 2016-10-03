package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Stack

package object param {

  case class MinAccumFrames(count: Int)
  implicit object MinAccumFrames extends Stack.Param[MinAccumFrames] {
    val default = MinAccumFrames(2)
  }

  case class PriorKnowledge(assumed: Boolean)
  implicit object PriorKnowledge extends Stack.Param[PriorKnowledge] {
    val default = PriorKnowledge(true)
  }
}
