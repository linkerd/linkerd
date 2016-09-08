package io.buoyant.router.http

import com.twitter.finagle.http.Request
import com.twitter.finagle.{Path, Dtab}
import com.twitter.finagle.buoyant.Dst
import com.twitter.util.{Try, Future}
import io.buoyant.router.RoutingFactory

case class HeaderIdentifier(
  prefix: Path,
  header: String,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  def apply(req: Request): Future[(Dst, Request)] = {
    req.headerMap.get(header) match {
      case Some(value) =>
        val path = Try(Path.read(value)).getOrElse(Path.Utf8(value))
        Future.value(Dst.Path(prefix ++ path, baseDtab(), Dtab.local), req)
      case None =>
        Future.exception(new IllegalArgumentException(s"$header header is absent"))
    }
  }
}
