package io.buoyant.namerd

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.{DeserializationContext, JsonDeserializer, JsonSerializer, SerializerProvider}
import com.fasterxml.jackson.databind.module.SimpleModule
import com.twitter.finagle.{NameTree, Path}

object DtabCodec {
  def module = {
    val module = new SimpleModule

    module.addSerializer(classOf[Path], new JsonSerializer[Path] {
      override def serialize(path: Path, json: JsonGenerator, p: SerializerProvider): Unit =
        json.writeString(path.show)
    })
    module.addDeserializer(classOf[Path], new JsonDeserializer[Path] {
      override def deserialize(json: JsonParser, ctx: DeserializationContext) =
        Path.read(json.getValueAsString)
    })

    module.addSerializer(classOf[NameTree[Path]], new JsonSerializer[NameTree[Path]] {
      override def serialize(
        nameTree: NameTree[Path],
        json: JsonGenerator,
        p: SerializerProvider
      ): Unit = json.writeString(nameTree.show)
    })

    module.addDeserializer(classOf[NameTree[Path]], new JsonDeserializer[NameTree[Path]] {
      override def deserialize(json: JsonParser, ctx: DeserializationContext) =
        NameTree.read(json.getValueAsString)
    })

    module
  }
}
