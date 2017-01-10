package io.buoyant

import com.twitter.finagle.{Name, NameTree, Namer, Path}
import com.twitter.util.{Activity, Try}

/**
 * A helper
 */
trait RewritingNamer extends Namer {
  protected[this] def rewrite(orig: Path): Option[Path]

  def lookup(path: Path): Activity[NameTree[Name]] =
    Activity.value(path match {
      case path if path.size > 0 =>
        rewrite(path) match {
          case Some(path) => NameTree.Leaf(Name.Path(path))
          case None => NameTree.Neg
        }
      case _ => NameTree.Neg
    })
}

private object HostColonPort {
  // do very coarse host matching so that we can support dns names,
  // ipv4, and ipv6 without going crazy.
  val hostRE = """^[A-Za-z0-9.:_-]+$""".r

  object PortStr {
    def unapply(s: String): Option[Int] = {
      val port = try s.toInt catch { case _: java.lang.NumberFormatException => -1 }
      if (0 < port && port < math.pow(2, 16)) Some(port) else None
    }
  }

  def unapply(s: String): Option[(String, Int)] = s.split(":") match {
    case Array(host, PortStr(port)) => Some((host, port))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /pfx/host:port/etc
 *
 * and rewrites to:
 *
 *   /pfx/host/port/etc
 */
class hostportPfx extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(2) match {
    case Path.Utf8(pfx, HostColonPort(host, port)) =>
      Some(Path.Utf8(pfx, host, port.toString) ++ path.drop(2))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /host:port/pfx/etc
 *
 * and rewrites to:
 *
 *   /pfx/port/host/etc
 */
class porthostPfx extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(2) match {
    case Path.Utf8(pfx, HostColonPort(host, port)) =>
      Some(Path.Utf8(pfx, port.toString, host) ++ path.drop(2))
    case _ => None
  }
}
