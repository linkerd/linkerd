package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.ClusterCache.Cluster
import io.buoyant.k8s.istio.{ClusterCache, DiscoveryClient, RouteManager}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}
import istio.proxy.v1.config.RouteRule

class IstioIdentifier(pfx: Path, baseDtab: () => Dtab, routeManager: RouteManager, clusterCache: ClusterCache) extends Identifier[Request] {

  def externalRequestPath(host: String): Path = {
    host.split(":") match {
      case Array(h: String, p: String) => pfx ++ Path.Utf8("ext", h, p)
      case Array(h: String) => pfx ++ Path.Utf8("ext", h, "80")
      case _ => throw new IllegalArgumentException("unable to parse host for request")
    }
  }

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    req.host match {
      case Some(host) =>
        Future.join(clusterCache.get(host), routeManager.getRules).map {
          case (Some(Cluster(dest, port)), rules: Map[String, RouteRule]) =>
            val filteredRules: Seq[(String, RouteRule)] = rules.filter {
              //TODO: add more route conditions
              case (_, r) => r.`destination` == Some(dest)
            }.toSeq

            if (filteredRules.isEmpty) {
              //forward requests which have no matching rules to an empty label selector
              pfx ++ Path.Utf8("dest", dest, "::", port)
            } else {
              //choose matching rule with the highest precedence
              val topRule = filteredRules.maxBy[Int] { case (m: String, d: RouteRule) => d.`precedence`.getOrElse(0) }
              pfx ++ Path.Utf8("route", topRule._1, port)
            }
          case b =>
            // forward requests which have no matching vhosts to external
            externalRequestPath(host)
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

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    val routeManager = RouteManager.getManagerFor(host, port)
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )
    val clusterCache = new ClusterCache(discoveryClient)
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
