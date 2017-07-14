package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.Request
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Dtab, Path, Service, http}
import com.twitter.util.{Future, Try}
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.ClusterCache.Cluster
import io.buoyant.k8s.istio._
import io.buoyant.k8s.{ClientConfig, IngressCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}

class IstioIngressIdentifier(
  val pfx: Path,
  baseDtab: () => Dtab,
  namespace: Option[String],
  apiClient: Service[http.Request, http.Response],
  annotationClass: String,
  routeCache: RouteCache,
  clusterCache: ClusterCache
) extends Identifier[Request] with IdentifierPreconditions {

  private[this] val ingressCache = new IngressCache(namespace, apiClient, annotationClass)

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val matchingPath = ingressCache.matchPath(req.host, req.path)
    matchingPath.flatMap {
      case None =>
        Future.value(new UnidentifiedRequest(s"no ingress rule matches ${req.host}:${req.path}"))
      case Some(ingressPath) =>
        val clusterName = s"${ingressPath.svc}.${ingressPath.namespace}.svc.cluster.local"

        // use clusterCache to transform any port Int into a port name
        val portName = Try(ingressPath.port.toInt).toOption match {
          case Some(portNumber) => clusterCache.get(s"$clusterName:$portNumber").map {
            case Some(Cluster(_, p)) => Some(p)
            case None => None
          }
          case None => Future.value(Some(ingressPath.port))
        }

        Future.join(portName, routeCache.getRules).map {
          case (Some(port), rules) =>
            //TODO: match on request scheme
            val meta = IstioRequestMeta(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get)
            val filteredRules = filterRules(rules, clusterName, meta)
            val path = maxPrecedenceRule(filteredRules) match {
              case Some((ruleName, rule)) =>
                val (uri, authority) = httpRewrite(rule, req.uri, req.host)
                req.uri = uri
                req.host = authority.getOrElse("")
                pfx ++ Path.Utf8("route", ruleName, port)
              //forward requests which have no matching rules to an empty label selector
              case None => pfx ++ Path.Utf8("dest", clusterName, "::", port)
            }
            val dst = Dst.Path(path, baseDtab(), Dtab.local)
            new IdentifiedRequest(dst, req)
          case _ => new UnidentifiedRequest(s"ingress path ${ingressPath.svc}:${ingressPath.port} does not match any istio vhosts")
        }
    }
  }
}

case class IstioIngressIdentifierConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String],
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends HttpIdentifierConfig with ClientConfig {
  @JsonIgnore
  override def portNum: Option[Int] = port.map(_.port)

  override def newIdentifier(
    prefix: Path,
    baseDtab: () => Dtab = () => Dtab.base
  ): Identifier[Request] = {
    val k8sApiserverClient = mkClient(Params.empty).configured(Label("ingress-identifier"))
    val host = apiserverHost.getOrElse(DefaultApiserverHost)
    val port = apiserverPort.map(_.port).getOrElse(DefaultApiserverPort)
    val routeCache = RouteCache.getManagerFor(host, port)
    val discoveryClient = DiscoveryClient(
      discoveryHost.getOrElse(DefaultDiscoveryHost),
      discoveryPort.map(_.port).getOrElse(DefaultDiscoveryPort)
    )
    val clusterCache = new ClusterCache(discoveryClient)
    new IstioIngressIdentifier(prefix, baseDtab, namespace, k8sApiserverClient.newService(dst), "istio", routeCache, clusterCache)
  }
}

object IstioIngressIdentifierConfig {
  val kind = "io.l5d.k8s.istio-ingress"
}

class IstioIngressIdentifierInitializer extends IdentifierInitializer {
  val configClass = classOf[IstioIngressIdentifierConfig]
  override val configId = IstioIngressIdentifierConfig.kind
}

object IstioIngressIdentifierInitializer extends IstioIngressIdentifierInitializer
