package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import com.twitter.finagle.Path
import io.buoyant.linkerd.config.{ConfigSerializer, ConfigDeserializer}

class PathDeserializer extends ConfigDeserializer[Path] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Path =
    catchMappingException(ctxt) {
      Path.read(_parseString(jp, ctxt))
    }
}

class PathSerializer extends ConfigSerializer[Path] {
  override def serialize(
    value: Path,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.show)
}
