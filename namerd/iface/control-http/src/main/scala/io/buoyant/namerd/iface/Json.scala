package io.buoyant.namerd.iface

import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.twitter.finagle.http._
import com.twitter.finagle.{Status => _, _}
import com.twitter.io.Buf
import com.twitter.util._
import io.buoyant.namerd.{DtabCodec => DtabModule}

object Json {

  private[this] val mapper = new ObjectMapper with ScalaObjectMapper
  mapper.registerModule(DefaultScalaModule)
  mapper.registerModule(DtabModule.module)

  def read[T: Manifest](buf: Buf): Try[T] = {
    val Buf.ByteBuffer.Owned(bb) = Buf.ByteBuffer.coerce(buf)
    Try { mapper.readValue[T](bb.array) }
  }

  def write[T](t: T): Buf =
    Buf.ByteArray.Owned(mapper.writeValueAsBytes(t))
}

sealed trait DtabCodec {
  def mediaTypes: Set[String]
  def write(dtab: Dtab): Buf
  def read(buf: Buf): Try[Dtab]
}

object DtabCodec {

  object JsonCodec extends DtabCodec {
    val mediaTypes = Set(MediaType.Json)
    def write(dtab: Dtab) = Json.write(dtab)
    def read(buf: Buf) =
      Json.read[IndexedSeq[Dentry]](buf).map(Dtab(_))
  }

  object TextCodec extends DtabCodec {
    val mediaTypes = Set("application/dtab", MediaType.Txt)
    def write(dtab: Dtab) = Buf.Utf8(dtab.show)
    def read(buf: Buf) = {
      val Buf.Utf8(d) = buf
      Try { Dtab.read(d) }
    }
  }

  def accept(types: Seq[String]): Option[(String, DtabCodec)] =
    types.map(_.toLowerCase).foldLeft[Option[(String, DtabCodec)]](None) {
      case (None, mt) => byMediaType(mt).map(mt -> _)
      case (t, _) => t
    }

  def byMediaType(ct: String): Option[DtabCodec] =
    if (JsonCodec.mediaTypes(ct)) Some(DtabCodec.JsonCodec)
    else if (TextCodec.mediaTypes(ct)) Some(DtabCodec.TextCodec)
    else None

  val default = (MediaType.Json, JsonCodec)
}
