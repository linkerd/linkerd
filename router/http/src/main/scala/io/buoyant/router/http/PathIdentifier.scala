package io.buoyant.router.http

import com.twitter.finagle.{Dtab, Path, http}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory

case class PathIdentifier(
  prefix: Path,
  segments: Int = 1,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[http.Request] {

  def apply(req: http.Request): Future[Dst] =
    Path.read(req.path) match {
      case path if path.size >= segments =>
        Future.value(Dst.Path(prefix ++ path.take(segments), baseDtab(), Dtab.local))
      case _ =>
        Future.exception(new IllegalArgumentException(s"not enough segments in path ${req.path}"))
    }

}
