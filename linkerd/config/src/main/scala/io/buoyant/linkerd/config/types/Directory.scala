package io.buoyant.linkerd.config.types

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import io.buoyant.linkerd.config.ConfigDeserializer
import java.nio.file.Paths

case class Directory(path: java.nio.file.Path) {
  require(path.toFile.isDirectory, s"$path is not a directory")
}

class DirectoryDeserializer extends ConfigDeserializer[Directory] {
  override def deserialize(jp: JsonParser, ctxt: DeserializationContext): Directory = catchMappingException(ctxt) {
    Directory(Paths.get(_parseString(jp, ctxt)))
  }
}
