package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import io.buoyant.linkerd.config.ConfigDeserializer

case class Port(port: Int) {
  val MinValue = 0
  val MaxValue = math.pow(2, 16) - 1
  require((MinValue <= port) && (port <= MaxValue), s"$port outside valid range for ports")
}

class PortDeserializer extends ConfigDeserializer[Port] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Port = catchMappingException(ctxt) {
    Port(_parseInteger(jp, ctxt))
  }
}
