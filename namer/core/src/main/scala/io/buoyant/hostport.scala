package io.buoyant

import com.twitter.finagle.Path

/**
 * Extracts a host and port from a "host:port" string.
 *
 * Does not not support IPv6 host IPs (because ipv6 notation doesn't
 * work in Paths as-is, due to bracket characters).
 */
private object HostColonPort {
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
 *   /pfx/host:port/etc
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
