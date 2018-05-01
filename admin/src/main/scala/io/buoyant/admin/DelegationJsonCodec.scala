package io.buoyant.admin

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.{Dentry, NameTree, Path}
import com.twitter.io.Buf
import com.twitter.util.Try

object DelegationJsonCodec {
  def mkModule(): SimpleModule = {
    val module = new SimpleModule

    module.addSerializer(classOf[Path], new JsonSerializer[Path] {
      override def serialize(path: Path, json: JsonGenerator, p: SerializerProvider) {
        json.writeString(path.show)
      }
    })
    module.addDeserializer(classOf[Path], new JsonDeserializer[Path] {
      override def deserialize(json: JsonParser, ctx: DeserializationContext) =
        Path.read(json.getValueAsString)
    })

    module.addSerializer(classOf[Dentry.Prefix], new JsonSerializer[Dentry.Prefix] {
      override def serialize(pfx: Dentry.Prefix, json: JsonGenerator, p: SerializerProvider) {
        json.writeString(pfx.show)
      }
    })
    module.addDeserializer(classOf[Dentry.Prefix], new JsonDeserializer[Dentry.Prefix] {
      override def deserialize(json: JsonParser, ctx: DeserializationContext) =
        Dentry.Prefix.read(json.getValueAsString)
    })

    module.addSerializer(classOf[NameTree[Path]], new JsonSerializer[NameTree[Path]] {
      override def serialize(
        nameTree: NameTree[Path],
        json: JsonGenerator,
        p: SerializerProvider
      ) {
        json.writeString(nameTree.show)
      }
    })

    module.addDeserializer(classOf[NameTree[Path]], new JsonDeserializer[NameTree[Path]] {
      override def deserialize(json: JsonParser, ctx: DeserializationContext) =
        NameTree.read(json.getValueAsString)
    })

    module
  }

  val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
  mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
  mapper.registerModule(mkModule())

  def writeStr[T](t: T): String = mapper.writeValueAsString(t)
  def writeBuf[T](t: T): Buf = Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
  def readBuf[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteBuffer.Owned(bb) = Buf.ByteBuffer.coerce(buf)
    Try { mapper.readValue[T](bb.array) }
  }
}
