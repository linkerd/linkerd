package io.buoyant.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import com.twitter.finagle.thrift.Protocols
import io.buoyant.config.{ConfigDeserializer, ConfigSerializer}
import org.apache.thrift.protocol.{TCompactProtocol, TProtocolFactory}

sealed trait ThriftProtocol {
  def factory: TProtocolFactory
  def name: String
}
object ThriftProtocol {
  object Binary extends ThriftProtocol {
    def factory = Protocols.binaryFactory()
    val name = "binary"
  }
  object Compact extends ThriftProtocol {
    def factory = new TCompactProtocol.Factory
    val name = "compact"
  }
}

class ThriftProtocolDeserializer extends ConfigDeserializer[ThriftProtocol] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): ThriftProtocol =
    catchMappingException(ctxt) {
      _parseString(jp, ctxt) match {
        case "binary" => ThriftProtocol.Binary
        case "compact" => ThriftProtocol.Compact
        case protocol =>
          throw new IllegalArgumentException(s"unsupported thrift protocol $protocol")
      }
    }
}

class ThriftProtocolSerializer extends ConfigSerializer[ThriftProtocol] {
  override def serialize(
    value: ThriftProtocol,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.name)
}
