package com.twitter.finagle.buoyant

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.twitter.finagle.Stack
import com.twitter.finagle.transport.Transport
import com.twitter.util.Duration

case class SocketOptionsConfig(
  noDelay: Boolean = true,
  reuseAddr: Boolean = true,
  reusePort: Boolean = false,
  @JsonDeserialize(contentAs = classOf[java.lang.Long]) writeTimeoutMs: Option[Long] = None,
  @JsonDeserialize(contentAs = classOf[java.lang.Long]) readTimeoutMs: Option[Long] = None,
  keepAlive: Option[Boolean] = None
) {
  def params: Stack.Params = {
    val writeTimeout: Duration = writeTimeoutMs match {
      case Some(milliseconds) => Duration.fromMilliseconds(milliseconds)
      case _ => Duration.Top
    }

    val readTimeout: Duration = readTimeoutMs match {
      case Some(milliseconds) => Duration.fromMilliseconds(milliseconds)
      case _ => Duration.Top
    }

    Stack.Params.empty +
      Transport.Options(noDelay, reuseAddr, reusePort) +
      Transport.Liveness(writeTimeout, readTimeout, keepAlive)
  }

}
