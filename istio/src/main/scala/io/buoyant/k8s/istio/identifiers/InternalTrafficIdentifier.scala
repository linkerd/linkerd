package io.buoyant.k8s.istio.identifiers

import com.twitter.finagle.buoyant.Dst
import com.twitter.finagle.{Dtab, Path}
import com.twitter.util.Future
import io.buoyant.k8s.istio.Cluster
import io.buoyant.k8s.istio.mixer.MixerClient
import io.buoyant.k8s.istio.{ClusterCache, IstioRequest, RouteCache}
import io.buoyant.router.RoutingFactory.{IdentifiedRequest, RequestIdentification}

class InternalTrafficIdentifier[Req](
  pfx: Path,
  baseDtab: () => Dtab,
  routeCache: RouteCache,
  clusterCache: ClusterCache,
  mixerClient: MixerClient,
  requestHandler: IstioProtocolSpecificRequestHandler[Req]
) extends IstioIdentifierBase[Req] {

  def identify(istioRequest: IstioRequest[Req]): Future[RequestIdentification[Req]] = {
    Future.join(clusterCache.get(istioRequest.authority), routeCache.getRules).flatMap {
      case (Some(Cluster(dest, port)), rules) =>
        //TODO: match on request scheme
        val filteredRules = filterRules(rules, dest, istioRequest)
        maxPrecedenceRule(filteredRules) match {
          case Some((ruleName, rule)) =>
            rule.`redirect` match {
              case Some(redir) => requestHandler.redirectRequest(redir, istioRequest.req)
              case None =>
                val (uri, authority) = httpRewrite(rule, istioRequest)
                requestHandler.rewriteRequest(uri, authority, istioRequest.req)
                Future.value(pfx ++ Path.Utf8("route", ruleName, port))
            }
          //forward requests which have no matching rules to an empty label selector
          case None => Future.value(pfx ++ Path.Utf8("dest", dest, "::", port))
        }
      case _ =>
        // forward requests which have no matching vhosts to external
        Future.value(externalRequestPath(istioRequest.authority))
    }.map { path =>
      val dst = Dst.Path(path, baseDtab(), Dtab.local)
      new IdentifiedRequest(dst, istioRequest.req)
    }
  }

  private def externalRequestPath(host: String): Path = {
    host.split(":") match {
      case Array(h: String, p: String) => pfx ++ Path.Utf8("ext", h, p)
      case Array(h: String) => pfx ++ Path.Utf8("ext", h, "80")
      case _ => throw new IllegalArgumentException("unable to parse host for request")
    }
  }
}
