package io.buoyant

import com.twitter.finagle.Path
import com.twitter.io.Buf
import com.twitter.util.{Activity, Future}

package object namerd {
  type Ns = String

  implicit class RichActivity[T](val activity: Activity[T]) extends AnyVal {
    /** A Future representing the first non-pending value of this Activity */
    def toFuture: Future[T] = activity.values.toFuture.flatMap(Future.const)
  }

  val Hash = Buf.Utf8("#")
  val Dollar = Buf.Utf8("$")
  val NotHashOrDollar: Buf => Boolean =
    (b: Buf) => !(b == Hash || b == Dollar)
  val Percent = Path.Utf8("%")

  def stripTransformerPrefix(p: Path): Path =
    if (p.startsWith(Percent))
      Path(p.elems.dropWhile(NotHashOrDollar): _*)
    else
      p
}
