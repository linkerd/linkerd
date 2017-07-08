package io.buoyant.linkerd.protocol

<<<<<<< HEAD
import io.buoyant.linkerd.ProtocolException

case class FramingException(reason: String)
  extends ProtocolException(reason)
=======
case class FramingException(reason: String) extends Exception(reason)
>>>>>>> 81a624b0f88c20a86898acff7fe3209aaf0f50b0
