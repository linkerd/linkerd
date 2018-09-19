package io.buoyant.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import io.buoyant.config.{ConfigSerializer, ConfigDeserializer}

case class Port(port: Int) {
  val MinValue = 0
  val MaxValue = math.pow(2, 16) - 1
  require((MinValue <= port) && (port <= MaxValue), s"$port outside valid range for ports")
}

class PortDeserializer extends ConfigDeserializer[Port] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Port = {
    val port = Port(jp.getIntValue)
    port
  }
}

class PortSerializer extends ConfigSerializer[Port] {
  override def serialize(
    value: Port,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeNumber(value.port)
}
