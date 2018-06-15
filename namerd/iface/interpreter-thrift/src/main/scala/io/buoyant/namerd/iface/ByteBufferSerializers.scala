package io.buoyant.namerd.iface

import com.twitter.io.Buf
import java.nio.ByteBuffer

object ByteBufferSerializers {

  def utf8(bb: ByteBuffer): String =
    Buf.Utf8.unapply(Buf.ByteBuffer.Shared(bb)).get

  def path(path: Seq[ByteBuffer]): String =
    path.map(utf8).mkString("/", "/", "")

  def stamp(bb: ByteBuffer): String =
    bb.duplicate().getLong.toString

  def ipv4(bb: ByteBuffer): String = {
    val dup = bb.duplicate()
    s"${dup.get()}.${dup.get()}.${dup.get()}.${dup.get()}"
  }
}
