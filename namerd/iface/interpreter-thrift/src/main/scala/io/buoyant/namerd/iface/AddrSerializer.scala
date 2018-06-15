package io.buoyant.namerd.iface

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import io.buoyant.config.ConfigSerializer
import io.buoyant.namerd.iface.{thriftscala => thrift}

class AddrSerializer extends ConfigSerializer[thrift.Addr] {
  import ByteBufferSerializers._

  override def serialize(
    value: thrift.Addr,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {

    gen.writeStartObject()
    gen.writeStringField("stamp", stamp(value.stamp))
    value.value match {
      case bound: thrift.AddrVal.Bound =>
        gen.writeStringField("type", "bound")
        gen.writeArrayFieldStart("addresses")
        for (address <- bound.bound.addresses) {
          gen.writeStartObject()
          gen.writeStringField("ip", ipv4(address.ip))
          gen.writeNumberField("port", address.port)
          for (meta <- address.meta) {
            gen.writeObjectFieldStart("meta")
            for (authority <- meta.authority) gen.writeStringField("authority", authority)
            for (nodeName <- meta.nodeName) gen.writeStringField("nodeName", nodeName)
            for (endpointAddrWeight <- meta.endpointAddrWeight) gen.writeNumberField("endpointAddrWeight", endpointAddrWeight)
            gen.writeEndObject()
          }
          gen.writeEndObject()
        }
        gen.writeEndArray()
      case neg: thrift.AddrVal.Neg =>
        gen.writeStringField("type", "neg")
      case unknown: thrift.AddrVal.UnknownUnionField =>
        gen.writeStringField("type", "unknown")
    }
    gen.writeEndObject()
  }
}
