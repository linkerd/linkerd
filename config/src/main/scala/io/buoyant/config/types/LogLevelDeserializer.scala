
package io.buoyant.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import com.twitter.logging.Level
import io.buoyant.config.{ConfigSerializer, ConfigDeserializer}

class LogLevelDeserializer extends ConfigDeserializer[Level] {

  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Level = {
    val lname = _parseString(jp, ctxt)
    Level.parse(lname.toUpperCase).getOrElse {
      throw new IllegalArgumentException(s"Illegal log level: $lname")
    }
  }
}

class LogLevelSerializer extends ConfigSerializer[Level] {
  override def serialize(
    level: Level,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(level.name)
}
