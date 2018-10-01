package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack
import com.twitter.finagle.transport.Transport

case class SocketOptionsConfig(
  disableTcpNoDelay: Boolean = true,
  reuseAddrEnabled: Boolean = true,
  reusePortEnabled: Boolean = false
) {
  def params = Stack.Params.empty +
    Transport.Options(disableTcpNoDelay, reuseAddrEnabled, reusePortEnabled)

}
