package io.buoyant.linkerd.protocol.http

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.ClusterCache.Cluster
import io.buoyant.k8s.istio.{ClusterCache, IdentifierPreconditions, RouteCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification}

class IstioIdentifier(val pfx: Path, baseDtab: () => Dtab, routeCache: RouteCache, clusterCache: ClusterCache)
  extends Identifier[Request] with IdentifierPreconditions {

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    req.host match {
      case Some(host) =>
        Future.join(clusterCache.get(host), routeCache.getRules).map {
          case (Some(Cluster(dest, port)), rules) =>
            //TODO: match on request scheme
            val meta = IstioRequestMeta(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get)
            val filteredRules = filterRules(rules, dest, meta)
            maxPrecedenceRule(filteredRules) match {
              case Some((ruleName, rule)) =>
                val (uri, authority) = httpRewrite(rule, req.uri, req.host)
                req.uri = uri
                req.host = authority.getOrElse("")
                pfx ++ Path.Utf8("route", ruleName, port)
              //forward requests which have no matching rules to an empty label selector
              case None => pfx ++ Path.Utf8("dest", dest, "::", port)
            }
          case _ =>
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

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    import io.buoyant.k8s.istio._

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
