package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.twitter.finagle.thrift.Protocols
import io.buoyant.linkerd.config.ConfigDeserializer
import org.apache.thrift.protocol.{TCompactProtocol, TProtocolFactory}

class ThriftProtocolDeserializer extends ConfigDeserializer[TProtocolFactory] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): TProtocolFactory =
    catchMappingException(ctxt) {
      _parseString(jp, ctxt) match {
        case "binary" => Protocols.binaryFactory()
        case "compact" => new TCompactProtocol.Factory()
        case protocol =>
          throw new IllegalArgumentException(s"unsupported thrift protocol $protocol")
      }
    }
}
