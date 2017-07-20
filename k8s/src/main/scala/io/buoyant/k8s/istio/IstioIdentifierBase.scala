package io.buoyant.k8s.istio

import com.twitter.finagle.Path
import com.twitter.util.Future
import io.buoyant.k8s.istio.ClusterCache.Cluster
import istio.proxy.v1.config.StringMatch.OneofMatchType
import istio.proxy.v1.config._

trait IstioIdentifierBase[Req] {

  /**
   * Defines a request's metadata for istio rules to match against
   * (normalizes fields between http and h2)
   */
  case class IstioRequestMeta(
    uri: String,
    scheme: String,
    method: String,
    authority: String,
    getHeader: (String) => Option[String]
  )

  def clusterCache: ClusterCache
  def routeCache: RouteCache
  def pfx: Path

  def redirectRequest(redir: HTTPRedirect, req: Req): Future[Nothing]
  def rewriteRequest(uri: String, authority: Option[String], req: Req): Unit
  def reqToMeta(req: Req): IstioRequestMeta

  def headerMatches(headerValue: String, stringMatch: StringMatch): Boolean = {
    stringMatch.`matchType` match {
      case Some(OneofMatchType.Exact(value)) => headerValue == value
      case Some(OneofMatchType.Prefix(pfx)) => headerValue.startsWith(pfx)
      case Some(OneofMatchType.Regex(r)) => headerValue.matches(r)
      case None => throw new IllegalArgumentException("stringMatch missing matchType")
    }
  }

  def externalRequestPath(host: String): Path = {
    host.split(":") match {
      case Array(h: String, p: String) => pfx ++ Path.Utf8("ext", h, p)
      case Array(h: String) => pfx ++ Path.Utf8("ext", h, "80")
      case _ => throw new IllegalArgumentException("unable to parse host for request")
    }
  }

  def matchesAllConditions(req: IstioRequestMeta, matchCondition: MatchCondition): Boolean = {
    val matchesHeaders = matchCondition.`httpHeaders`.forall {
      case ("uri", m) => headerMatches(req.uri, m)
      case ("scheme", m) => headerMatches(req.scheme, m)
      case ("method", m) => headerMatches(req.method, m)
      case ("authority", m) => headerMatches(req.authority, m)
      case (headerName, stringMatch) =>
        req.getHeader(headerName) match {
          case Some(a) => headerMatches(a, stringMatch)
          case None => false
        }
    }
    //TODO: add other match conditions
    matchesHeaders
  }

  def getIdentifiedPath(req: Req): Future[Path] = {
    val meta = reqToMeta(req)
    Future.join(clusterCache.get(meta.authority), routeCache.getRules).flatMap {
      case (Some(Cluster(dest, port)), rules) =>
        //TODO: match on request scheme
        val filteredRules = filterRules(rules, dest, meta)
        maxPrecedenceRule(filteredRules) match {
          case Some((ruleName, rule)) =>
            rule.`redirect` match {
              case Some(redir) => redirectRequest(redir, req)
              case None =>
                val (uri, authority) = httpRewrite(rule, meta)
                rewriteRequest(uri, authority, req)
                Future.value(pfx ++ Path.Utf8("route", ruleName, port))
            }
          //forward requests which have no matching rules to an empty label selector
          case None => Future.value(pfx ++ Path.Utf8("dest", dest, "::", port))
        }
      case _ =>
        // forward requests which have no matching vhosts to external
        Future.value(externalRequestPath(meta.authority))
    }
  }

  /**
   * Rewrites uri and authority based on a route-rule. It doesn't modify
   * the request directly because it's used by both http and h2.
   * @param rule
   * @param meta
   * @return Tuple of uri and authority to used to rewrite headers on the request
   */
  def httpRewrite(rule: RouteRule, meta: IstioRequestMeta): (String, Option[String]) = {
    rule.`rewrite` match {
      case Some(HTTPRewrite(url, updatedAuth)) =>
        val updatedUri = url.map { replacement =>
          rule.`match`.flatMap(_.`httpHeaders`.get("uri").flatMap(_.`matchType`)) match {
            case Some(OneofMatchType.Prefix(pfx)) if meta.uri.startsWith(pfx) => meta.uri.replaceFirst(pfx, replacement)
            // meta.uri should always start with pfx, because it previously matched the pfx as part of headerMatches
            // if is doesn't start with pfx for some reason, leave it be
            case Some(OneofMatchType.Prefix(pfx)) => meta.uri
            case _ => replacement
          }
        }
        (updatedUri.getOrElse(meta.uri), updatedAuth.orElse(Some(meta.authority)))
      case _ => (meta.uri, Some(meta.authority))
    }
  }

  def filterRules(rules: Map[String, RouteRule], dest: String, req: IstioRequestMeta): Seq[(String, RouteRule)] = rules.filter {
    case (_, r) if r.`destination`.contains(dest) =>
      // return true if no match conditions were defined on the route-rule
      r.`match`.forall(matchesAllConditions(req, _))
    case _ => false
  }.toSeq

  def maxPrecedenceRule(rules: Seq[(String, RouteRule)]): Option[(String, RouteRule)] = {
    if (rules.isEmpty) {
      None
    } else {
      val rule = rules.maxBy[Int] { case (m: String, d: RouteRule) => d.`precedence`.getOrElse(0) }
      Some(rule)
    }
  }
}
