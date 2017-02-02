package com.twitter.finagle.buoyant.h2

import com.twitter.finagle.Stack

package object param {

  case class ClientPriorKnowledge(assumed: Boolean)
  implicit object ClientPriorKnowledge extends Stack.Param[ClientPriorKnowledge] {
    val default = ClientPriorKnowledge(true)
  }

  object Settings {

    case class HeaderTableSize(size: Option[Int])
    implicit object HeaderTableSize extends Stack.Param[HeaderTableSize] {
      val default = HeaderTableSize(None)
    }

    case class InitialWindowSize(size: Option[Int])
    implicit object InitialWindowSize extends Stack.Param[InitialWindowSize] {
      val default = InitialWindowSize(None)
    }

    case class MaxConcurrentStreams(streams: Option[Int])
    implicit object MaxConcurrentStreams extends Stack.Param[MaxConcurrentStreams] {
      val default = MaxConcurrentStreams(None)
    }

    case class MaxFrameSize(size: Option[Int])
    implicit object MaxFrameSize extends Stack.Param[MaxFrameSize] {
      val default = MaxFrameSize(None)
    }

    case class MaxHeaderListSize(size: Option[Int])
    implicit object MaxHeaderListSize extends Stack.Param[MaxHeaderListSize] {
      val default = MaxHeaderListSize(None)
    }
  }
}
