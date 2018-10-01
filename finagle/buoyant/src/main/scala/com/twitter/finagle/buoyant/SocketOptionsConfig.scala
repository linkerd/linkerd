package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack
import com.twitter.finagle.transport.Transport

case class SocketOptionsConfig(
  noDelay: Boolean = true,
  reuseAddr: Boolean = true,
  reusePort: Boolean = false
) {
  def params = Stack.Params.empty +
    Transport.Options(noDelay, reuseAddr, reusePort)

}
