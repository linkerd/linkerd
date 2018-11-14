package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack
import com.twitter.finagle.transport.Transport
import com.twitter.util.Duration

case class SocketOptionsConfig(
  noDelay: Boolean = true,
  reuseAddr: Boolean = true,
  reusePort: Boolean = false,
  writeTimeoutMs: Option[Long] = None,
  readTimeoutMs: Option[Long] = None,
  keepAlive: Option[Boolean] = None
) {
  def params: Stack.Params = {
    val writeTimeout: Duration = writeTimeoutMs match {
      case Some(seconds) => Duration.fromMilliseconds(seconds)
      case _ => Duration.Top
    }

    val readTimeout: Duration = readTimeoutMs match {
      case Some(seconds) => Duration.fromMilliseconds(seconds)
      case _ => Duration.Top
    }

    Stack.Params.empty +
      Transport.Options(noDelay, reuseAddr, reusePort) +
      Transport.Liveness(writeTimeout, readTimeout, keepAlive)
  }

}
