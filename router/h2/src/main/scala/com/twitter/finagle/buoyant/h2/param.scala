package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Stack

package object param {

  case class MinAccumFrames(count: Int)
  implicit object MinAccumFrames extends Stack.Param[MinAccumFrames] {
    val default = MinAccumFrames(2)
  }

  case class ClientPriorKnowledge(assumed: Boolean)
  implicit object ClientPriorKnowledge extends Stack.Param[ClientPriorKnowledge] {
    val default = ClientPriorKnowledge(true)
  }
}
