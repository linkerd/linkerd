package io.buoyant.router.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.http.Request
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification}

class StaticIdentifier(
  prefix: Path,
  path: Path,
  baseDtab: () => Dtab = () => Dtab.base
) extends RoutingFactory.Identifier[Request] {

  def apply(req: Request): Future[RequestIdentification[Request]] = {
    val dst = Dst.Path(prefix ++ path, baseDtab(), Dtab.local)
    Future.value(new IdentifiedRequest(dst, req))
  }
}
