package io.buoyant.consul.v1

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import io.buoyant.config.{ConfigDeserializer, ConfigSerializer}

sealed trait ConsistencyMode

object ConsistencyMode {

  case object Default extends ConsistencyMode
  case object Stale extends ConsistencyMode
  case object Consistent extends ConsistencyMode

}

class ConsistencyModeDeserializer extends ConfigDeserializer[ConsistencyMode] {

  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): ConsistencyMode =
    _parseString(jp, ctxt) match {
      case "default" => ConsistencyMode.Default
      case "stale" => ConsistencyMode.Stale
      case "consistent" => ConsistencyMode.Consistent
      case unknown =>
        throw new IllegalArgumentException(s"Illegal Consul consistency mode level: $unknown")
    }
}

class ConsistencyModeSerializer extends ConfigSerializer[ConsistencyMode] {
  override def serialize(
    mode: ConsistencyMode,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(mode.toString.toLowerCase) // case objects get good toString methods
}
