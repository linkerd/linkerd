package io.buoyant.router.http

import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Request, Version}
import com.twitter.util.Future
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}

object MethodTenantHostIdentifier {

  val TenantHeader = "X-Medallia-Rpc-Tenant"

  def mk(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = MethodTenantHostIdentifier(prefix, baseDtab, TenantHeader)
}

case class MethodTenantHostIdentifier(
  prefix: Path,
  baseDtab: () => Dtab = () => Dtab.base,
  tenantHeader: String
) extends Identifier[Request] {

  private[this] def mkPath(path: Path): Dst.Path =
    Dst.Path(prefix ++ path, baseDtab(), Dtab.local)

  def apply(req: Request): Future[RequestIdentification[Request]] = req.version match {
    case Version.Http11 =>
      req.host match {
        case Some(host) if host.nonEmpty =>
          req.headerMap.get(tenantHeader) match {
            case Some(tenant) =>
              val dst = mkPath(Path.Utf8("1.1", req.method.toString, tenant, host))
              Future.value(new IdentifiedRequest(dst, req))
            case None =>
              Future.value(new UnidentifiedRequest(s"$tenantHeader header is absent"))
          }
        case _ =>
          Future.value(
            new UnidentifiedRequest(s"${Version.Http11} request missing Host header")
          )
      }
    case _ =>
      Future.value(
        new UnidentifiedRequest(s"${req.version} not supported by this identifier")
      )
  }
}
