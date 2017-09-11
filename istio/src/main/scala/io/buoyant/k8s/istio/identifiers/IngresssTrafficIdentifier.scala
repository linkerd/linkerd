package io.buoyant.k8s.istio.identifiers

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.{Future, Try}
import io.buoyant.k8s.IngressPath
import io.buoyant.k8s.istio.Cluster
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.k8s.istio.{ClusterCache, IstioRequest, RouteCache}
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification, UnidentifiedRequest}

class IngresssTrafficIdentifier[Req](
  pfx: Path,
  baseDtab: () => Dtab,
  routeCache: RouteCache,
  clusterCache: ClusterCache,
  mixerClient: MixerClient,
  requestHandler: IstioProtocolSpecificRequestHandler[Req]
) extends IstioIdentifierBase[Req] {

  def identify(istioRequest: IstioRequest[Req], matchingPath: Future[Option[IngressPath]]): Future[RequestIdentification[Req]] = {
    matchingPath.flatMap {
      case None =>
        Future.value(new UnidentifiedRequest(s"no ingress rule matches ${istioRequest}"))
      case Some(ingressPath) =>
        val clusterName = s"${ingressPath.svc}.${ingressPath.namespace}.svc.cluster.local"

        // use clusterCache to transform any port Int into a port name
        //TODO: make this not use exceptions for conditional logic
        val portName = Try(ingressPath.port.toInt).toOption match {
          case Some(portNumber) =>
            clusterCache.
              get(s"$clusterName:$portNumber").map {
                case Some(Cluster(_, p)) => Some(p)
                case None => None
              }
          case None => Future.value(Some(ingressPath.port))
        }

        Future.join(portName, routeCache.getRules).flatMap {
          case (Some(port), rules) =>
            val filteredRules = filterRules(rules, clusterName, istioRequest)
            (maxPrecedenceRule(filteredRules) match {
              case Some((ruleName, rule)) =>
                rule.`redirect` match {
                  case Some(redir) => requestHandler.redirectRequest(redir, istioRequest.req)
                  case None =>
                    val (uri, authority) = httpRewrite(rule, istioRequest)
                    requestHandler.rewriteRequest(uri, authority, istioRequest.req)
                    Future.value(pfx ++ Path.Utf8("route", ruleName, port))
                }
              //forward requests which have no matching rules to an empty label selector
              case None => Future.value(pfx ++ Path.Utf8("dest", clusterName, "::", port))
            }).map { path =>
              val dst = Dst.Path(path, baseDtab(), Dtab.local)
              new IdentifiedRequest(dst, istioRequest.req)
            }
          case _ => Future.value(new UnidentifiedRequest(s"ingress path ${ingressPath.svc}:${ingressPath.port} does not match any istio vhosts"))
        }
    }
  }

}
