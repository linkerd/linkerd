package io.buoyant.interpreter.mesh

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import io.buoyant.config.ConfigSerializer
import io.linkerd.mesh
import io.linkerd.mesh.Endpoint

class EndpointSerializer extends ConfigSerializer[mesh.Endpoint] {

  override def serialize(
    value: Endpoint,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    gen.writeStartObject()
    for (iaf <- value.inetAf) gen.writeObjectField("inetAf", iaf)
    for (addr <- value.address) gen.writeStringField("address", BufSerializers.ipv4(addr))
    for (meta <- value.meta) gen.writeObjectField("meta", meta)
    for (metadata <- value.metadata) gen.writeObjectField("metadata", metadata)
    for (port <- value.port) gen.writeStringField("port", port.toString)
    gen.writeEndObject()
  }
}
