package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.twitter.finagle.Dtab
import io.buoyant.linkerd.config.ConfigDeserializer

class DtabDeserializer extends ConfigDeserializer[Dtab] {
  override def deserialize(parser: JsonParser, ctx: DeserializationContext): Dtab = catchMappingException(ctx) {
    Dtab.read(_parseString(parser, ctx))
  }
}
