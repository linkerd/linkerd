package io.buoyant.namerd

import com.twitter.finagle.Path
import com.twitter.io.Buf

object Paths {
  val ConfiguredNamerPrefix = Buf.Utf8("#")
  val LoadedNamerPrefix = Buf.Utf8("$")
  val NotHashOrDollar: Buf => Boolean =
    (b: Buf) => !(b == ConfiguredNamerPrefix || b == LoadedNamerPrefix)
  val TransformerPrefix = Path.Utf8("%")

  def stripTransformerPrefix(p: Path): Path =
    if (p.startsWith(TransformerPrefix))
      Path(p.elems.dropWhile(NotHashOrDollar): _*)
    else
      p
}
