package com.twitter.finagle.buoyant.h2

import scala.util.control.NoStackTrace

sealed trait Error
object Error {
  sealed trait ConnectionError { _: Error => }
  sealed trait StreamError { _: Error => }

  object NoError extends Error with ConnectionError with StreamError {
    override def toString = "Error.NoError"
  }

  object ProtocolError extends Error with ConnectionError {
    override def toString = "Error.ProtocolError"
  }

  object InternalError extends Error with ConnectionError with StreamError {
    override def toString = "Error.InternalError"
  }

  object EnhanceYourCalm extends Error with ConnectionError with StreamError {
    override def toString = "Error.EnhanceYourCalm"
  }

  object RefusedStream extends Error with StreamError {
    override def toString = "Error.RefusedStream"
  }

  object StreamClosed extends Error with StreamError {
    override def toString = "Error.StreamClosed"
  }

  object Cancel extends Error with StreamError {
    override def toString = "Error.Cancel"
  }

  case class ResetException(error: StreamError)
    extends Exception(s"Error.Reset($error)") with NoStackTrace
}
