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
    val updated = mutable.ArrayBuffer.empty[String]
    def dropOrAdd(test: Boolean, str: String): Unit = if (!test) {
      updated += str
    }

    val tree = path match {
      case Path.Utf8(v@"1.0" , method, rest@_*) =>
        dropOrAdd(dropVersion, v)
        dropOrAdd(dropMethod, method)
        NameTree.Leaf(Name.Path(dstPrefix ++ Path.Utf8(updated ++ rest: _*)))

      case Path.Utf8(v@"1.1", method, host, rest@_*) =>
        dropOrAdd(dropVersion, v)
        dropOrAdd(dropMethod, method)
        dropOrAdd(dropHost, host)
        NameTree.Leaf(Name.Path(dstPrefix ++ Path.Utf8(updated ++ rest: _*)))

      case _ => NameTree.Neg
    }

    Activity.value(tree)
  }

}
