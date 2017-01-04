package io.buoyant.namer

import com.twitter.finagle.Path
import com.twitter.io.Buf

object Paths {
  val ConfiguredNamerBuf = Buf.Utf8("#")
  val ConfiguredNamerPrefix = Path(ConfiguredNamerBuf)
  val LoadedNamerBuf = Buf.Utf8("$")
  val LoadedNamerPrefix = Path(LoadedNamerBuf)
  val NotHashOrDollar: Buf => Boolean =
    (b: Buf) => !(b == ConfiguredNamerBuf || b == LoadedNamerBuf)
  val TransformerPrefix = Path.Utf8("%")

  def stripTransformerPrefix(p: Path): Path =
    if (p.startsWith(TransformerPrefix))
      Path(p.elems.dropWhile(NotHashOrDollar): _*)
    else
      p
}
