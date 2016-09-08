package io.buoyant.router.http

import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path, http}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{UnidentifiedRequest, IdentifiedRequest, RequestIdentification}

case class PathIdentifier(
  prefix: Path,
  segments: Int = 1,
  consume: Boolean = false,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[http.Request] {

  def apply(req: http.Request): Future[RequestIdentification[Request]] =
    req.path.split("/").drop(1) match {
      case path if path.size >= segments =>
        if (consume) {
          req.uri = req.uri.split("/").drop(segments + 1).mkString("/", "/", "")
        }
        Future.value(
          new IdentifiedRequest[Request](
            Dst.Path(prefix ++ Path.Utf8(path.take(segments): _*), baseDtab(), Dtab.local),
            req
          )
        )
      case _ =>
        Future.value(
          new UnidentifiedRequest[Request]("not enough segments in path", req)
        )
    }
}
