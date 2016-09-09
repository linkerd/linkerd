package io.buoyant.router.http

import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path, http}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{RequestIdentification, UnidentifiedRequest, IdentifiedRequest}

object MethodAndHostIdentifier {
  def mk(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): RoutingFactory.Identifier[http.Request] = MethodAndHostIdentifier(prefix, false, baseDtab)
}

case class MethodAndHostIdentifier(
  prefix: Path,
  uris: Boolean = false,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[http.Request] {

  private[this] def suffix(req: http.Request): Path =
    if (uris) Path.read(req.path) else Path.empty

  private[this] def mkPath(path: Path): Dst.Path =
    Dst.Path(prefix ++ path, baseDtab(), Dtab.local)

  def apply(req: http.Request): Future[RequestIdentification[Request]] = req.version match {
    case http.Version.Http10 =>
      Future.value(
        new IdentifiedRequest[Request](mkPath(Path.Utf8("1.0", req.method.toString) ++ suffix(req)), req)
      )

    case http.Version.Http11 =>
      req.host match {
        case Some(host) if host.nonEmpty =>
          val dst = mkPath(Path.Utf8("1.1", req.method.toString, host) ++ suffix(req))
          Future.value(new IdentifiedRequest(dst, req))
        case _ =>
          Future.value(
            new UnidentifiedRequest[Request](
              s"${http.Version.Http11} request missing hostname"
            )
          )
      }
  }
}
