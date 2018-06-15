package io.buoyant.config.types

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.twitter.io.Buf
import io.buoyant.config.ConfigSerializer
import java.nio.ByteBuffer

class ByteBufferSerializer extends ConfigSerializer[ByteBuffer] {
  override def serialize(
    value: ByteBuffer,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    val Buf.Utf8(s) = Buf.ByteBuffer.Shared(value)
    jgen.writeString(s)
  }
}
