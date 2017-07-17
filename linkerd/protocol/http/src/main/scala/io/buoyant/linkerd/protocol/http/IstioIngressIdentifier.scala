package io.buoyant.linkerd.protocol.http

import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle.Stack.Params
import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.finagle.param.Label
import com.twitter.finagle.{Dtab, Path, Service, http}
import com.twitter.util.{Future, Try}
import io.buoyant.config.types.Port
import io.buoyant.k8s.istio.ClusterCache.Cluster
import io.buoyant.k8s.istio._
import io.buoyant.k8s.{ClientConfig, IngressCache}
import io.buoyant.linkerd.IdentifierInitializer
import io.buoyant.linkerd.protocol.HttpIdentifierConfig
import io.buoyant.linkerd.protocol.http.ErrorResponder.HttpResponseException
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, Identifier, RequestIdentification, UnidentifiedRequest}
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

  def reqToMeta(req: Request): IstioRequestMeta =
    //TODO: match on request scheme
    IstioRequestMeta(req.path, "", req.method.toString, req.host.getOrElse(""), req.headerMap.get)

  def redirectRequest(redir: HTTPRedirect, req: Request): Future[Nothing] = {
    val redirect = Response(Status.Found)
    redirect.location = redir.`uri`.getOrElse(req.uri)
    redirect.host = redir.`authority`.orElse(req.host).getOrElse("")
    Future.exception(HttpResponseException(redirect))
  }

  def rewriteRequest(uri: String, authority: Option[String], req: Request): Unit = {
    req.uri = uri
    req.host = authority.getOrElse("")
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
