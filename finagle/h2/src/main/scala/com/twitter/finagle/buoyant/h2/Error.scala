package com.twitter.finagle.buoyant.h2

import scala.util.control.NoStackTrace

sealed trait Error
  extends Throwable
  with NoStackTrace

sealed trait GoAway extends Error
object GoAway {
  object EnhanceYourCalm extends GoAway { override def toString = "GoAway.EnhanceYourCalm" }
  object InternalError extends GoAway { override def toString = "GoAway.InternalError" }
  object NoError extends GoAway { override def toString = "GoAway.NoError" }
  object ProtocolError extends GoAway { override def toString = "GoAway.ProtocolError" }
}

sealed trait Reset extends Error
object Reset {
  object Cancel extends Reset { override def toString = "Reset.Cancel" }
  object Closed extends Reset { override def toString = "Reset.Closed" }
  object CompressionError extends Reset { override def toString = "Reset.CompressionError" }
  object ConnectError extends Reset { override def toString = "Reset.ConnectError" }
  object EnhanceYourCalm extends Reset { override def toString = "Reset.EnhanceYourCalm" }
  object FlowControlError extends Reset { override def toString = "Reset.FlowControlError" }
  object InadequateSecurity extends Reset { override def toString = "Reset.InadequateSecurity" }
  object InternalError extends Reset { override def toString = "Reset.InternalError" }
  object ProtocolError extends Reset { override def toString = "Reset.ProtocolError" }
  object NoError extends Reset { override def toString = "Reset.NoError" }
  object Refused extends Reset { override def toString = "Reset.Refused" }
  object DoesNotExist extends Reset { override def toString = "Reset.DoesNotExist" }
  object SettingsTimeout extends Reset { override def toString = "Reset.SettingsTimeout" }
  object StreamClosed extends Reset { override def toString = "Reset.StreamClosed" }
}

/**
 * When an exception is encountered in a stream, it is wrapepd in a
 * StreamError to indicate whether the error originated from the
 * remote (network) or local (application) side of the stream.
 */
sealed trait StreamError
  extends Throwable
  with NoStackTrace {

  def cause: Throwable
}

object StreamError {
  case class Local(cause: Throwable) extends StreamError {
    override def toString = s"StreamError.Local(${cause.toString})"
    override def getMessage = s"local: ${cause.getMessage}"
  }

  case class Remote(cause: Throwable) extends StreamError {
    override def toString = s"StreamError.Remote(${cause.toString})"
    override def getMessage = s"remote: ${cause.getMessage}"
  }

  def unapply(e: StreamError): Option[Throwable] = Some(e.cause)
}
