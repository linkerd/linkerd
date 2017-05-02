package io.buoyant.router.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Request, Version}
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest}

object MethodAndHostIdentifier {
  def mk(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): RoutingFactory.Identifier[Request] = MethodAndHostIdentifier(prefix, false, baseDtab)
}

case class MethodAndHostIdentifier(
  prefix: Path,
  uris: Boolean = false,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  private[this] def suffix(req: Request): Path =
    if (uris) Path.read(req.path) else Path.empty

  private[this] def mkPath(path: Path): Dst.Path =
    Dst.Path(prefix ++ path, baseDtab(), Dtab.local)

  def apply(req: Request): Future[RequestIdentification[Request]] = req.version match {
    case Version.Http10 =>
      val dst = mkPath(Path.Utf8("1.0", req.method.toString) ++ suffix(req))
      Future.value(new IdentifiedRequest(dst, req))

    case Version.Http11 =>
      req.host match {
        case Some(host) if host.nonEmpty =>
          val dst = mkPath(Path.Utf8("1.1", req.method.toString, host.toLowerCase) ++ suffix(req))
          Future.value(new IdentifiedRequest(dst, req))
        case _ =>
          Future.value(
            new UnidentifiedRequest(
              s"${Version.Http11} request missing hostname"
            )
          )
      }
    case v =>
      Future.value(
        new UnidentifiedRequest(s"Unknown HTTP version $v")
      )
  }
}
