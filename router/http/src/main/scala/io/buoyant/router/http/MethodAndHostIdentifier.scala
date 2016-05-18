package io.buoyant.router.http

import com.twitter.finagle.{Dtab, Path, http}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory

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

  def apply(req: http.Request): Future[Dst] = req.version match {
    case http.Version.Http10 =>
      Future.value(mkPath(Path.Utf8("1.0", req.method.toString) ++ suffix(req)))

    case http.Version.Http11 =>
      req.host match {
        case Some(host) if host.nonEmpty =>
          Future.value(mkPath(Path.Utf8("1.1", req.method.toString, host) ++ suffix(req)))
        case _ =>
          Future.exception(new IllegalArgumentException(s"${http.Version.Http11} request missing hostname: $req"))
      }
  }

}
