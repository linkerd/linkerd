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
      Activity.value(NameTree.Leaf(
        Resolver.eval(s"inet!$host:$port") match {
          case bound: Name.Bound => Name.Bound(bound.addr, rinet.prefix ++ Path.read(s"/$port/$host"), path.drop(2))
          case name => name
        }
      ))
    case _ =>
      Activity.value(NameTree.Neg)
  }
}

object rinet {
  val prefix = Path.read("/$/io.buoyant.rinet")
}
