package com.twitter.finagle.buoyant.h2

import scala.util.control.NoStackTrace

sealed trait Error

sealed trait GoAway
  extends Throwable
  with NoStackTrace
  with Error

object GoAway {

  object EnhanceYourCalm extends GoAway { override def toString = "GoAway.EnhanceYourCalm" }
  object InternalError extends GoAway { override def toString = "GoAway.InternalError" }
  object NoError extends GoAway { override def toString = "GoAway.NoError" }
  object ProtocolError extends GoAway { override def toString = "GoAway.ProtocolError" }
}

sealed trait Reset
  extends Throwable
  with NoStackTrace
  with Error

object Reset {

  object Cancel extends Reset { override def toString = "Reset.Cancel" }
  object Closed extends Reset { override def toString = "Reset.Closed" }
  object EnhanceYourCalm extends Reset { override def toString = "Reset.EnhanceYourCalm" }
  object InternalError extends Reset { override def toString = "Reset.InternalError" }
  object NoError extends Reset { override def toString = "Reset.NoError" }
  object Refused extends Reset { override def toString = "Reset.Refused" }
}
