package io.buoyant.interpreter.mesh

import com.twitter.io.Buf

object BufSerializers {

  def utf8(buf: Buf): String = Buf.Utf8.unapply(buf).get

  def path(path: Seq[Buf]): String = path.map(utf8).mkString("/", "/", "")

  def ipv4(addr: Buf): String = {
    val bytes = Buf.ByteArray.coerce(addr)
    s"${bytes.get(0)}.${bytes.get(1)}.${bytes.get(2)}.${bytes.get(3)}"
  }

}
