package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import com.google.common.net.InetAddresses
import io.buoyant.linkerd.config.{ConfigSerializer, ConfigDeserializer}
import java.net.InetAddress

class InetAddressDeserializer extends ConfigDeserializer[InetAddress] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): InetAddress =
    catchMappingException(ctxt) {
      InetAddresses.forString(_parseString(jp, ctxt))
    }
}

class InetAddressSerializer extends ConfigSerializer[InetAddress] {
  override def serialize(
    value: InetAddress,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.getHostName)
}
