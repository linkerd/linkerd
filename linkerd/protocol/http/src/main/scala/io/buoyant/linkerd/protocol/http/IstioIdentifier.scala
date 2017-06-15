package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Path}
import com.twitter.finagle.http.Request
import com.twitter.finagle.param.Label
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.{ClientConfig, IstioPilotClient, RouteManager}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}
import com.twitter.conversions.time._
import istio.proxy.v1.config.RouteRule

class IstioIdentifier(pfx: Path, baseDtab: () => Dtab, routeManager: RouteManager) extends Identifier[Request] {
  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"no matching istio rules found")

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    routeManager.getRules().map { rules =>
      val filteredRules = rules.filter {
        case (_, r) => r.`destination` == req.host
      }
      if (filteredRules.isEmpty) {
        unidentified
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
  port: Option[Port],
  pollIntervalMs: Option[Long]
) extends HttpIdentifierConfig with ClientConfig {
  @JsonIgnore
  override val DefaultPort = 8081
  @JsonIgnore
  override val DefaultHost = "istio-manager.default.svc.cluster.local"

  @JsonIgnore
  def portNum = port.map(_.port)

  @JsonIgnore
  private[this] val DefaultPollInterval = 5.seconds
  @JsonIgnore
  private[this] val pollInterval = pollIntervalMs.map(_.millis).getOrElse(DefaultPollInterval)

  override protected def getHost = host.getOrElse(DefaultHost)
  override protected def getPort = portNum.getOrElse(DefaultPort)

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val client = mkClient(Params.empty).configured(Label("istio-route-manager"))
    val api = new IstioPilotClient(client.newService(dst))
    val routeManager = new RouteManager(api, pollInterval)
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
