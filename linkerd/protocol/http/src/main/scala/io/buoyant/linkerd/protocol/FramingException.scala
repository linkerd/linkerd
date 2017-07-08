package io.buoyant.linkerd.protocol

import io.buoyant.linkerd.ProtocolException

case class FramingException(reason: String)
  extends ProtocolException(reason, "HTTP")
