package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import com.twitter.finagle.Dtab
import io.buoyant.linkerd.config.{ConfigSerializer, ConfigDeserializer}

class DtabDeserializer extends ConfigDeserializer[Dtab] {
  override def deserialize(parser: JsonParser, ctx: DeserializationContext): Dtab =
    catchMappingException(ctx) {
      Dtab.read(_parseString(parser, ctx))
    }
}

class DtabSerializer extends ConfigSerializer[Dtab] {
  override def serialize(
    value: Dtab,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.show)
}
