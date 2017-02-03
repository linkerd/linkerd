package io.buoyant.router.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Version.{Http10, Http11}
import com.twitter.finagle.http.{Request, Version}
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest}

object HostIdentifier {
  def mk(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): RoutingFactory.Identifier[Request] = HostIdentifier(prefix, baseDtab)
}

case class HostIdentifier(
  prefix: Path,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  private[this] def mkPath(path: Path): Dst.Path =
    Dst.Path(prefix ++ path, baseDtab(), Dtab.local)

  private[this] def version(request: Request) = request.version match {
    case Http11 => "1.1"
    case Http10 => "1.0"
  }

  def apply(req: Request): Future[RequestIdentification[Request]] = req.host match {
    case Some(host) if host.nonEmpty =>
      val dst = mkPath(Path.Utf8(version(req), host.toLowerCase))
      Future.value(new IdentifiedRequest(dst, req))
    case _ =>
      Future.value(new UnidentifiedRequest(s"${req.version} request missing hostname"))
  }
}

