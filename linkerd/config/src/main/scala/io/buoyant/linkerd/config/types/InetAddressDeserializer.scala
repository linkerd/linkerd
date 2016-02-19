package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.google.common.net.InetAddresses
import io.buoyant.linkerd.config.ConfigDeserializer
import java.net.InetAddress

class InetAddressDeserializer extends ConfigDeserializer[InetAddress] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): InetAddress = catchMappingException(ctxt) {
    InetAddresses.forString(_parseString(jp, ctxt))
  }
}
