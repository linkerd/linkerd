package io.buoyant.interpreter.mesh

import com.twitter.io.Buf

object BufSerializers {

  def utf8(buf: Buf): String = Buf.Utf8.unapply(buf).get

  def path(path: Seq[Buf]): String = path.map(utf8).mkString("/", "/", "")

  def ipv4(addr: Buf): String = {
    val bytes = Buf.ByteArray.Owned.extract(addr).map(_ & (-1 >>> 24))

    s"${bytes(0)}.${bytes(1)}.${bytes(2)}.${bytes(3)}"
  }

}
