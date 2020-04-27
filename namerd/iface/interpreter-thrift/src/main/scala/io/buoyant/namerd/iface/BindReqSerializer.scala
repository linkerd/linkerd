package io.buoyant.namerd.iface

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import io.buoyant.config.ConfigSerializer
import io.buoyant.namerd.iface.{thriftscala => thrift}

class BindReqSerializer extends ConfigSerializer[thrift.BindReq] {
  import ByteBufferSerializers._

  override def serialize(
    value: thrift.BindReq,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    gen.writeStartObject()
    gen.writeStringField("name", path(value.name.name.toIndexedSeq))
    gen.writeStringField("dtab", value.dtab)
    gen.writeStringField("stamp", stamp(value.name.stamp))
    gen.writeStringField("namespace", value.name.ns)
    gen.writeStringField("clientId", path(value.clientId.toIndexedSeq))
    gen.writeEndObject()
  }
}
