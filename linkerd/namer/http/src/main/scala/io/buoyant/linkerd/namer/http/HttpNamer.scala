package io.buoyant.linkerd.namer.http

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.Activity
import scala.collection.mutable

class HttpNamer(
  dstPrefix: Path,
  dropVersion: Boolean = false,
  dropMethod: Boolean = false,
  dropHost: Boolean = false
) extends Namer {
  def lookup(path: Path): Activity[NameTree[Name]] = {
    val tree = path match {
      case Path.Utf8(version@("1.0" | "1.1"), method, host, rest@_*) =>
        val path = mutable.ArrayBuffer.empty[String]
        if (!dropVersion) {
          path += version
        }
        if (!dropMethod) {
          path += method
        }
        if (!dropHost) {
          path += host
        }
        NameTree.Leaf(Name.Path(dstPrefix ++ Path.Utf8(path ++ rest: _*)))
      case _ => NameTree.Neg
    }
    Activity.value(tree)
  }
}
