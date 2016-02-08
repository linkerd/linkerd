package io.buoyant.http

import com.twitter.finagle.{Name, NameTree, Namer, Path, Service, ServiceNamer}
import com.twitter.finagle.http._
import com.twitter.util.{Activity, Future, Try}

private object Match {
  // do very coarse host matching so that we can support dns names,
  // ipv4, and ipv6 without going crazy.
  val host = """^[A-Za-z0-9.:-]+$""".r
  val method = "[A-Z]+".r

  def subdomain(domain: String, hostname: String): Option[String] = {
    val sfx = s".$domain"
    if (hostname endsWith sfx) Some(hostname.dropRight(sfx.length)) else None
  }
}

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
      Some(Path.Utf8(host.split("\\.").reverse: _*) ++ path.drop(1))
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
      Some(Path.Utf8(pfx +: host.split("\\.").reverse: _*) ++ path.drop(2))
    case _ => None
  }
}

/**
 * A service namer that accepts names in the form:
 *
 *   /400/resource/name
 *
 * and binds the name to an Http service that always responds with the
 * given status code (i.e. 400).
 */
class status extends ServiceNamer[Request, Response] {

  private[this] object Code {
    def unapply(s: String): Option[Status] =
      Try(s.toInt).toOption.filter { s => 100 <= s && s < 600 } map (Status.fromCode)
  }

  private[this] case class StatusService(code: Status) extends Service[Request, Response] {
    def apply(req: Request): Future[Response] = {
      val rsp = Response()
      rsp.status = code
      Future.value(rsp)
    }
  }

  def lookupService(path: Path): Option[Service[Request, Response]] = path.take(1) match {
    case Path.Utf8(Code(status)) => Some(StatusService(status))
    case _ => None
  }
}
