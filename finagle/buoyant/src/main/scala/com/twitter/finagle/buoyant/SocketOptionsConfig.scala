package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack
import com.twitter.finagle.transport.Transport
import com.twitter.util.Duration

case class SocketOptionsConfig(
  noDelay: Boolean = true,
  reuseAddr: Boolean = true,
  reusePort: Boolean = false,
  writeTimeoutSeconds: Option[Integer] = None,
  readTimeoutSeconds: Option[Integer] = None,
  keepAlive: Option[Boolean] = None
) {
  def params: Stack.Params = {
    val writeTimeout: Duration = writeTimeoutSeconds match {
      case Some(seconds) => Duration.fromSeconds(seconds)
      case _ => Duration.Top
    }

    val readTimeout: Duration = readTimeoutSeconds match {
      case Some(seconds) => Duration.fromSeconds(seconds)
      case _ => Duration.Top
    }

    Stack.Params.empty +
      Transport.Options(noDelay, reuseAddr, reusePort) +
      Transport.Liveness(writeTimeout, readTimeout, keepAlive)
  }

}
