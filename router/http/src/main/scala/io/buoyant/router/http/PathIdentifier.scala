package io.buoyant.router.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest}

case class PathIdentifier(
  prefix: Path,
  segments: Int = 1,
  consume: Boolean = false,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  private[this] val TooFewSegments =
    Future.value(new UnidentifiedRequest[Request]("not enough segments in path"))

  def apply(req: Request): Future[RequestIdentification[Request]] =
    req.path.split("/").drop(1) match {
      case path if path.size >= segments =>
        if (consume) {
          req.uri = req.uri.split("/").drop(segments + 1) match {
            case Array() => "/"
            case x =>
              val trailingSlash = if (req.uri.endsWith("/")) "/" else ""
              x.mkString("/", "/", trailingSlash)
          }
        }
        val dst = Dst.Path(prefix ++ Path.Utf8(path.take(segments): _*), baseDtab(), Dtab.local)
        Future.value(new IdentifiedRequest[Request](dst, req))
      case _ =>
        TooFewSegments
    }
}
