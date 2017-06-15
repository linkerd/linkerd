package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Http, Path}
import com.twitter.finagle.http.Request
import com.twitter.finagle.param.Label
import com.twitter.finagle.tracing.NullTracer
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.ClusterCache.Cluster
import io.buoyant.k8s.{ClientConfig, ClusterCache, RouteManager, SetHostFilter}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}
import istio.proxy.v1.config.RouteRule

class IstioIdentifier(pfx: Path, baseDtab: () => Dtab, routeManager: RouteManager, clusterCache: ClusterCache) extends Identifier[Request] {
  private[this] val unidentified: RequestIdentification[Request] =
    new UnidentifiedRequest(s"no matching istio rules found")

  def forwardedRequestPath(host: String): Path = {
    host.split(":") match {
      case Array(h: String, p: String) => pfx ++ Path.Utf8("dest", h, p)
      case Array(h: String) => pfx ++ Path.Utf8("dest", h, "80")
      case _ => throw new IllegalArgumentException("unable to parse host for request")
    }
  }

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    req.host match {
      case Some(host) =>
        Future.join(clusterCache.get(host), routeManager.getRules()).map {
          case (Some(Cluster(dest, port)), rules) =>
            val filteredRules: Seq[(String, RouteRule)] = rules.filter {
              //TODO: add more route conditions
              case (_, r) => r.`destination` == Some(dest)
            }.toSeq

            if (filteredRules.isEmpty) {
              //forward requests which have no matching rules
              forwardedRequestPath(host)
            } else {
              //choose matching rule with the highest precedence
              val topRule = filteredRules.maxBy[Int] { case (m: String, d: RouteRule) => d.`precedence`.getOrElse(0) }
              pfx ++ Path.Utf8("route", topRule._1, port)
            }
          case b =>
            // forward requests which have no matching vhosts
            forwardedRequestPath(host)
        }.map { path =>
          val dst = Dst.Path(path, baseDtab(), Dtab.local)
          new IdentifiedRequest(dst, req)
        }
      case None => throw new IllegalArgumentException("no host found for request")
    }
  }
}

case class IstioIdentifierConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends HttpIdentifierConfig {
  //TODO: DRY up with IstioInterpreter
  @JsonIgnore
  val DefaultDiscoveryHost = "istio-manager.default.svc.cluster.local"
  @JsonIgnore
  val DefaultDiscoveryPort = 8080

  @JsonIgnore
  val DefaultApiserverHost = "istio-manager.default.svc.cluster.local"
  @JsonIgnore
  val DefaultApiserverPort = 8081

  @JsonIgnore
  private[this] def discoveryClient() = {
    val host = discoveryHost.getOrElse(DefaultDiscoveryHost)
    val port = discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    val setHost = new SetHostFilter(host, port)
    Http.client
      .withTracer(NullTracer)
      .withStreaming(true)
      .filtered(setHost)
      .newService(s"/$$/inet/$host/$port", "istio-identifier-cluster-cache")
  }

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    val routeManager = RouteManager.getManagerFor(host, port)
    val clusterCache = new ClusterCache(discoveryClient())
    new IstioIdentifier(prefix, baseDtab, routeManager, clusterCache)
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
