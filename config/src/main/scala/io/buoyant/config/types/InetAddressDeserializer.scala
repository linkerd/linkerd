package io.buoyant.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import com.google.common.net.InetAddresses
import io.buoyant.config.{ConfigDeserializer, ConfigSerializer}
import java.net.InetAddress

class InetAddressDeserializer extends ConfigDeserializer[InetAddress] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): InetAddress =
    InetAddresses.forString(_parseString(jp, ctxt))
}

class InetAddressSerializer extends ConfigSerializer[InetAddress] {
  override def serialize(
    value: InetAddress,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.getHostName)
}
