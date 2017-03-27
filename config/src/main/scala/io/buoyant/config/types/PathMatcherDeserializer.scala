package io.buoyant.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import com.twitter.finagle.buoyant.PathMatcher
import io.buoyant.config.{ConfigSerializer, ConfigDeserializer}

class PathMatcherDeserializer extends ConfigDeserializer[PathMatcher] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): PathMatcher =
    catchMappingException(ctxt) {
      PathMatcher(_parseString(jp, ctxt))
    }
}

class PathMatcherSerializer extends ConfigSerializer[PathMatcher] {
  override def serialize(
    value: PathMatcher,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.toString)
}
