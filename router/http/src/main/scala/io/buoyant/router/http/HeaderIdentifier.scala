package io.buoyant.router.http

import com.twitter.finagle.http.Request
import com.twitter.finagle.{Path, Dtab}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.{Try, Future}
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{UnidentifiedRequest, IdentifiedRequest, RequestIdentification}

case class HeaderIdentifier(
  prefix: Path,
  header: String,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  def apply(req: Request): Future[RequestIdentification[Request]] = {
    req.headerMap.get(header) match {
      case Some(value) =>
        val identified = Try {
          val path = Path.read(value)
          val dst = Dst.Path(prefix ++ path, baseDtab(), Dtab.local)
          new IdentifiedRequest(dst, req)
        }
        Future.const(identified)
      case None =>
        Future.value(new UnidentifiedRequest(s"$header header is absent"))
    }
  }
}
