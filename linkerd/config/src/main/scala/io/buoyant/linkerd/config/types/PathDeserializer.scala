package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.twitter.finagle.Path
import io.buoyant.linkerd.config.ConfigDeserializer

class PathDeserializer extends ConfigDeserializer[Path] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Path = catchMappingException(ctxt) {
    Path.read(_parseString(jp, ctxt))
  }
}
