package io.buoyant.namer.fs

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.SerializerProvider
import com.twitter.io.Buf
import com.twitter.util.Activity
import io.buoyant.config.ConfigSerializer

class UpRegSerializer extends ConfigSerializer[Watcher.File.UpReg] {

  override def serialize(
    value: Watcher.File.UpReg,
    gen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = {
    value.buf.sample() match {
      case Activity.Ok(Buf.Utf8(s)) => gen.writeString(s)
      case Activity.Pending => gen.writeString("pending")
      case Activity.Failed(e) => gen.writeString("error: " + e.getMessage)
    }
  }
}
