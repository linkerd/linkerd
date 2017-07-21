package io.buoyant.linkerd.protocol.h2

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.buoyant.h2.{Headers, Request, Response, Status, Stream}
import com.twitter.finagle.param.Label
import com.twitter.finagle._
import com.twitter.util.{Future, Try}
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.ClusterCache.Cluster
import io.buoyant.k8s.istio._
import io.buoyant.k8s.{ClientConfig, IngressCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.H2IdentifierConfig
import io.buoyant.linkerd.protocol.h2.ErrorReseter.H2ResponseException
import io.buoyant.router.RoutingFactory.{BaseDtab, DstPrefix, IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}
import istio.proxy.v1.config.HTTPRedirect

class IstioIngressIdentifier(
  val pfx: Path,
  baseDtab: () => Dtab,
  namespace: Option[String],
  apiClient: Service[http.Request, http.Response],
  annotationClass: String,
  val routeCache: RouteCache,
  val clusterCache: ClusterCache
) extends Identifier[Request] with IstioIdentifierBase[Request] {

  private[this] val ingressCache = new IngressCache(namespace, apiClient, annotationClass)

  override def apply(req: Request): Future[RequestIdentification[Request]] = {
    val matchingPath = ingressCache.matchPath(Some(req.authority), req.path)
    matchingPath.flatMap {
      case None =>
        Future.value(new UnidentifiedRequest(s"no ingress rule matches ${req.authority}:${req.path}"))
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

        Future.join(portName, routeCache.getRules).flatMap {
          case (Some(port), rules) =>
            val filteredRules = filterRules(rules, clusterName, reqToMeta(req))
            (maxPrecedenceRule(filteredRules) match {
              case Some((ruleName, rule)) =>
                rule.`redirect` match {
                  case Some(redir) => redirectRequest(redir, req)
                  case None =>
                    val (uri, authority) = httpRewrite(rule, reqToMeta(req))
                    rewriteRequest(uri, authority, req)
                    Future.value(pfx ++ Path.Utf8("route", ruleName, port))
                }
              //forward requests which have no matching rules to an empty label selector
              case None => Future.value(pfx ++ Path.Utf8("dest", clusterName, "::", port))
            }).map { path =>
              val dst = Dst.Path(path, baseDtab(), Dtab.local)
              new IdentifiedRequest(dst, req)
            }
          case _ => Future.value(new UnidentifiedRequest(s"ingress path ${ingressPath.svc}:${ingressPath.port} does not match any istio vhosts"))
        }
    }
  }

  def redirectRequest(redir: HTTPRedirect, req: Request): Future[Nothing] = {
    val resp = Response(Status.Found, Stream.empty())
    resp.headers.set(Headers.Path, redir.`uri`.getOrElse(req.path))
    resp.headers.set(Headers.Authority, redir.`authority`.getOrElse(req.authority))
    Future.exception(H2ResponseException(resp))
  }

  def rewriteRequest(uri: String, authority: Option[String], req: Request): Unit = {
    req.headers.set(Headers.Path, uri)
    req.headers.set(Headers.Authority, authority.getOrElse(""))
  }

  def reqToMeta(req: Request): IstioRequestMeta =
    IstioRequestMeta(req.path, req.scheme, req.method.toString, req.authority, req.headers.get)

}

case class IstioIngressIdentifierConfig(
  host: Option[String],
  port: Option[Port],
  namespace: Option[String],
  discoveryHost: Option[String],
  discoveryPort: Option[Port],
  apiserverHost: Option[String],
  apiserverPort: Option[Port]
) extends H2IdentifierConfig with ClientConfig {
  @JsonIgnore
  override def portNum: Option[Int] = port.map(_.port)

  override def newIdentifier(params: Stack.Params) = {
    import io.buoyant.k8s.istio._

    val DstPrefix(prefix) = params[DstPrefix]
    val BaseDtab(baseDtab) = params[BaseDtab]
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
