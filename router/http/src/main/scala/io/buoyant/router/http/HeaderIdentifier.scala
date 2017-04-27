package io.buoyant.router.http

import com.twitter.finagle.http.{Fields, Request}
import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.{Future, Try}
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest}

case class HeaderIdentifier(
  prefix: Path,
  header: String,
  headerPath: Boolean,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  def apply(req: Request): Future[RequestIdentification[Request]] = {
    req.headerMap.get(header) match {
      case None | Some("") =>
        Future.value(new UnidentifiedRequest(s"$header header is absent"))
      case Some(value) =>
        val identified = Try {
          val path = if (headerPath) Path.read(value) else Path.Utf8(value)
          val dst = Dst.Path(prefix ++ path, baseDtab(), Dtab.local)
          new IdentifiedRequest(dst, req)
        }
        Future.const(identified)

    }
  }
}

object HeaderIdentifier {
  def default(prefix: Path, baseDtab: () => Dtab = () => Dtab.base): HeaderIdentifier =
    new HeaderIdentifier(prefix, Fields.Host, false, baseDtab)
}
