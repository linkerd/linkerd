package io.buoyant.router
package h2

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.h2.Request
import com.twitter.util.Future

class PathIdentifier(pfx: Path, baseDtab: () => Dtab)
  extends RoutingFactory.Identifier[Request] {

  def apply(req: Request): Future[(Dst, Request)] = {
    val dst = Dst.Path(pfx ++ reqPath(req), baseDtab(), Dtab.empty)
    Future.value((dst, req))
  }

  private def reqPath(req: Request): Path = req.path match {
    case "" | "/" => Path.empty
    case UriPath(path) => Path.read(path)
  }

  private object UriPath {
    def unapply(uri: String): Option[String] =
      uri.indexOf('?') match {
        case -1 => Some(uri.stripSuffix("/"))
        case idx => Some(uri.substring(idx + 1).stripSuffix("/"))
      }
  }
}
