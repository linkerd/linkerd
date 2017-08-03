package io.buoyant

import com.twitter.finagle.Path

/**
 * Extracts a host and port from a "host:port" string.
 *
 * Does not not support IPv6 host IPs (because ipv6 notation doesn't
 * work in Paths as-is, due to bracket characters).
 */
private object HostColonPort {
  /**
   * regex for capturing strings that conform to the definition
   * of a "label" in RFCs 1035 and 1123, used for the `port` part
   * of a [[HostColonPort]].
   *
   * this is based on the `DNS_LABEL` regex in Kubernetes:
   * https://github.com/kubernetes/kubernetes/blob/master/pkg/api/types.go#L40-L43
   */
  private[this] val DnsLabel = """([a-z0-9][-a-z0-9]*[a-z0-9]?)""".r

  def unapply(s: String): Option[(String, String)] = s.split(":") match {
    case Array(host, DnsLabel(port)) // DNS labels may not exceed 63 characters in length
    if port.length <= 63 => Some((host, port))
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
      Some(Path.Utf8(pfx, host, port) ++ path.drop(2))
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
      Some(Path.Utf8(pfx, port, host) ++ path.drop(2))
    case _ => None
  }
}
