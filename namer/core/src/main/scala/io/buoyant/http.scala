package io.buoyant
package http

import com.twitter.finagle.{Name, Path}

/**
 * A set of utility namers that aren't _actually_ HTTP-specific (at
 * least in terms of types).
 */

private object Match {
  // do very coarse host matching so that we can support dns names,
  // ipv4, and ipv6 without going crazy.
  val host = """^[A-Za-z0-9.:_-]+$""".r
  val method = "[A-Z]+".r

  def dropPort(hostname: String): String = {
    val idx = hostname.indexOf(":")
    if (idx > 0) hostname.take(hostname.indexOf(":")) else hostname
  }

  def subdomain(domain: String, hostname: String): Option[String] = {
    val sfx = s".$domain"
    val host = dropPort(hostname)
    if (host endsWith sfx) Some(host.dropRight(sfx.length)) else None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /POST/svc/name/resource/name
 *
 * and rewrites to:
 *
 *   /svc/name/resource/name
 */
class anyMethod extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(1) match {
    case Path.Utf8(Match.method()) => Some(path.drop(1))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /pfx/METHOD/svc/name/resource/name
 *
 * and rewrites to:
 *
 *   /pfx/svc/name/resource/name
 */
class anyMethodPfx extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(2) match {
    case Path.Utf8(pfx, Match.method()) => Some(Path.Utf8(pfx) ++ path.drop(2))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /foo.buoyant.io/resource/name
 *
 * and rewrites to:
 *
 *   /resource/name
 */
class anyHost extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(1) match {
    case Path.Utf8(Match.host()) => Some(path.drop(1))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /pfx/foo.buoyant.io/resource/name
 *
 * and rewrites to:
 *
 *   /pfx/resource/name
 */
class anyHostPfx extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(2) match {
    case Path.Utf8(pfx, Match.host()) => Some(Path.Utf8(pfx) ++ path.drop(2))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /buoyant.io/foo.buoyant.io/resource/name
 *
 * and rewrites to:
 *
 *   /foo/resource/name
 */
class subdomainOf extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(2) match {
    case Path.Utf8(domain@Match.host(), host@Match.host()) =>
      Match.subdomain(domain, host).filter(_.length > 0).map { sub =>
        Path.Utf8(sub) ++ path.drop(2)
      }
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /buoyant.io/pfx/foo.buoyant.io/resource/name
 *
 * and rewrites to:
 *
 *   /pfx/foo/resource/name
 */
class subdomainOfPfx extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(3) match {
    case Path.Utf8(domain@Match.host(), pfx, host@Match.host()) =>
      Match.subdomain(domain, host).filter(_.length > 0).map { sub =>
        Path.Utf8(pfx, sub) ++ path.drop(3)
      }
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /foo.buoyant.io/resource/name
 *
 * and rewrites to:
 *
 *   /io/buoyant/foo/resource/name
 */
class domainToPath extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(1) match {
    case Path.Utf8(host@Match.host()) =>
      Some(Path.Utf8(host.split("\\.").reverse.toIndexedSeq: _*) ++ path.drop(1))
    case _ => None
  }
}

/**
 * A rewriting namer that accepts names in the form:
 *
 *   /pfx/foo.buoyant.io/resource/name
 *
 * and rewrites to:
 *
 *   /pfx/io/buoyant/foo/resource/name
 */
class domainToPathPfx extends RewritingNamer {
  protected[this] def rewrite(path: Path) = path.take(2) match {
    case Path.Utf8(pfx, host@Match.host()) =>
      Some(Path.Utf8(pfx +: host.split("\\.").reverse.toIndexedSeq: _*) ++ path.drop(2))
    case _ => None
  }
}
