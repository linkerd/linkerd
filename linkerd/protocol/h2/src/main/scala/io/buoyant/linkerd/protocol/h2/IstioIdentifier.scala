package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Headers, Request}
import com.twitter.finagle.{Dtab, Path, Stack}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.ClusterCache.Cluster
import io.buoyant.k8s.istio.{ClusterCache, IdentifierPreconditions, RouteCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.router.RoutingFactory.{BaseDtab, DstPrefix, IdentifiedRequest, Identifier, RequestIdentification}

class IstioIdentifier(val pfx: Path, baseDtab: () => Dtab, routeCache: RouteCache, clusterCache: ClusterCache)
  extends Identifier[Request] with IdentifierPreconditions {

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    Future.join(clusterCache.get(req.authority), routeCache.getRules).map {
      case (Some(Cluster(dest, port)), rules) =>
        val meta = IstioRequestMeta(req.path, req.scheme, req.method.toString, req.authority, req.headers.get)
        val filteredRules = filterRules(rules, dest, meta)
        maxPrecedenceRule(filteredRules) match {
          case Some((ruleName, rule)) =>
            val (uri, authority) = httpRewrite(rule, req.path, Some(req.authority))
            req.headers.set(Headers.Path, uri)
            req.headers.set(Headers.Authority, authority.getOrElse(""))
            pfx ++ Path.Utf8("route", ruleName, port)
          //forward requests which have no matching rules to an empty label selector
          case None => pfx ++ Path.Utf8("dest", dest, "::", port)
        }
      case b =>
        // forward requests which have no matching vhosts to external
        externalRequestPath(req.authority)
    }.map { path =>
      val dst = Dst.Path(path, baseDtab(), Dtab.local)
      new IdentifiedRequest(dst, req)
    }
  }
}

case class IstioIdentifierConfig(
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends H2IdentifierConfig {

  @JsonIgnore
  override def newIdentifier(params: Stack.Params) = {
    import io.buoyant.k8s.istio._

    val DstPrefix(prefix) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    val routeCache = RouteCache.getManagerFor(host, port)
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )
    val clusterCache = new ClusterCache(discoveryClient)
    new IstioIdentifier(prefix, baseDtab, routeCache, clusterCache)
  }
}

object IstioIdentifierConfig {
  val kind = "io.l5d.k8s.istio"
}

class IstioIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IstioIdentifierConfig]
  override val configId = IstioIdentifierConfig.kind
}

object IstioIdentifierInitializer extends IstioIdentifierInitializer
