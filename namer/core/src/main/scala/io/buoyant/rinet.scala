package io.buoyant

import com.twitter.finagle._
import com.twitter.util.Activity

/**
 * An alternate form of the inet namer that expects port before host.
 *
 *   /$/io.buoyant.rinet/<port>/<host>
 *
 * is equivalent to
 *
 *   /$/inet/<host>/<port>
 */
class rinet extends Namer {
  override def lookup(path: Path): Activity[NameTree[Name]] = path.take(2) match {
    case Path.Utf8(port, host) =>
      Activity.value(NameTree.Leaf(Resolver.eval(s"inet!$host:$port")))
    case _ =>
      Activity.value(NameTree.Neg)
  }
}
