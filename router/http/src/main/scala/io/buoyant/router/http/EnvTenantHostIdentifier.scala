package io.buoyant.router.http

import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}

object EnvTenantHostIdentifier {

  def mk(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = EnvTenantHostIdentifier(prefix, baseDtab)

}

/**
 * Identifier that creates a path based on these headers values (in order)
 * <ol>
 *   <li> X-Medallia-Rpc-Environment: Optional. "_" represents a cross environment request. Currently only used for QA Clusters.
 *   <li> X-Medallia-Rpc-Tenant: Required. Tenant for the request
 *   <li> Host: Required. Service name
 * </ol>
 */
case class EnvTenantHostIdentifier(
  prefix: Path,
  baseDtab: () => Dtab = () => Dtab.base
) extends Identifier[Request] {

  val HostHeader = "Host"

  val TenantHeader = "X-Medallia-Rpc-Tenant"

  val EnvironmentHeader = "X-Medallia-Rpc-Environment"

  private[this] def mkPath(path: Path): Dst.Path =
    Dst.Path(prefix ++ path, baseDtab(), Dtab.local)

  def apply(req: Request): Future[RequestIdentification[Request]] = {
    val tenant = getHeader(req, TenantHeader)
    val environment = getHeader(req, EnvironmentHeader)
    val host = getHeader(req, HostHeader)

    if (tenant.isEmpty) {
      Future.value(new UnidentifiedRequest(s"$TenantHeader header is absent"))
    } else if (host.isEmpty) {
      Future.value(new UnidentifiedRequest(s"$HostHeader header is absent"))
    } else {
      val dst = mkPath(Path.Utf8(environment.getOrElse("_"), tenant.get, host.get))
      Future.value(new IdentifiedRequest(dst, req))
    }
  }

  private def getHeader(req: Request, header: String): Option[String] = {
    req.headerMap.get(header) match {
      case None | Some("") =>
        None
      case Some(value) =>
        Some(value)
    }
  }
}
