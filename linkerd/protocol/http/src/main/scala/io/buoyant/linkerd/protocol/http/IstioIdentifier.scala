package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClientConfig
import io.buoyant.k8s.istio.RouteManager
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}
import istio.proxy.v1.config.RouteRule

class IstioIdentifier(pfx: Path, baseDtab: () => Dtab, routeManager: RouteManager) extends Identifier[Request] {
  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"no matching istio rules found")

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    routeManager.getRules.map { rules =>
      val filteredRules = rules.filter {
        //TODO: add more route conditions
        case (_, r) => r.`destination` == req.host
      }

      if (filteredRules.isEmpty) {
        //forward requests which have no matching rules
        req.host.map { host =>
          val path = host.split(":") match {
            case Array(h: String, p: String) => pfx ++ Path.Utf8("dest", h, p)
            case Array(h: String) => pfx ++ Path.Utf8("dest", h, "80")
            case _ => throw new IllegalArgumentException("unable to parse host for request")
          }
          val dst = Dst.Path(path, baseDtab(), Dtab.local)
          new IdentifiedRequest(dst, req)
        }.getOrElse(unidentified)
      } else {
        val topRule = filteredRules.maxBy[Int] { case (m: String, d: RouteRule) => d.`precedence`.getOrElse(0) }
        val path = pfx ++ Path.Utf8("route", topRule._1, "http") // HTTP port hardcoded for now
        val dst = Dst.Path(path, baseDtab(), Dtab.local)
        new IdentifiedRequest(dst, req)
      }
    }
  }
}

case class IstioIdentifierConfig(
  host: Option[String],
  port: Option[Port]
) extends HttpIdentifierConfig with ClientConfig {
  @JsonIgnore
  override val DefaultPort = 8081
  @JsonIgnore
  override val DefaultHost = "istio-manager.default.svc.cluster.local"

  @JsonIgnore
  def portNum = port.map(_.port)

  override protected def getHost = host.getOrElse(DefaultHost)
  override protected def getPort = portNum.getOrElse(DefaultPort)

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val routeManager = RouteManager.getManagerFor(getHost, getPort)
    new IstioIdentifier(prefix, baseDtab, routeManager)
  }
}

object IstioIdentifierConfig {
  val kind = "io.l5d.istio"
}

class IstioIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IstioIdentifierConfig]
  override val configId = IstioIdentifierConfig.kind
}

object IstioIdentifierInitializer extends IstioIdentifierInitializer
