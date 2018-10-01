package io.buoyant.config.types

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{SerializerProvider, DeserializationContext}
import io.buoyant.config.{ConfigSerializer, ConfigDeserializer}
import java.nio.file.Paths

case class File(path: java.nio.file.Path) {
  require(path.toFile.isFile, s"$path is not a file")
}

class FileDeserializer extends ConfigDeserializer[File] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): File =
    File(Paths.get(_parseString(jp, ctxt)))
}

class FileSerializer extends ConfigSerializer[File] {
  override def serialize(
    value: File,
    jgen: JsonGenerator,
    provider: SerializerProvider
  ): Unit = jgen.writeString(value.path.toString)
}
