package io.buoyant.interpreter.mesh

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import io.buoyant.config.ConfigSerializer
import io.linkerd.mesh

class PathSerializer extends ConfigSerializer[mesh.Path] {

  override def serialize(
    value: mesh.Path,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    gen.writeString(BufSerializers.path(value.elems))
  }
}
