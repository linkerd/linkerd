package io.buoyant.k8s.istio.identifiers

import io.buoyant.k8s.istio.IstioRequest
import istio.proxy.v1.config.StringMatch.OneofMatchType
import istio.proxy.v1.config.{HTTPRewrite, MatchCondition, RouteRule, StringMatch}

trait IstioIdentifierBase[Req] {

  def maxPrecedenceRule(rules: Seq[(String, RouteRule)]): Option[(String, RouteRule)] = {
    if (rules.isEmpty) {
      None
    } else {
      val rule = rules.maxBy[Int] { case (m: String, d: RouteRule) => d.`precedence`.getOrElse(0) }
      Some(rule)
    }
  }

  def filterRules(rules: Map[String, RouteRule], dest: String, req: IstioRequest[Req]): Seq[(String, RouteRule)] = rules.filter {
    case (_, r) if r.`destination`.contains(dest) =>
      // return true if no match conditions were defined on the route-rule
      r.`match`.forall(matchesAllConditions(req, _))
    case _ => false
  }.toSeq

  def matchesAllConditions(req: IstioRequest[Req], matchCondition: MatchCondition): Boolean = {
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

  def headerMatches(headerValue: String, stringMatch: StringMatch): Boolean = {
    stringMatch.`matchType` match {
      case Some(OneofMatchType.Exact(value)) => headerValue == value
      case Some(OneofMatchType.Prefix(pfx)) => headerValue.startsWith(pfx)
      case Some(OneofMatchType.Regex(r)) => headerValue.matches(r)
      case None => throw new IllegalArgumentException("stringMatch missing matchType")
    }
  }

  /**
   * Rewrites uri and authority based on a route-rule. It doesn't modify
   * the request directly because it's used by both http and h2.
   *
   * @param rule
   * @param meta
   * @return Tuple of uri and authority to used to rewrite headers on the request
   */
  def httpRewrite(rule: RouteRule, meta: IstioRequest[Req]): (String, Option[String]) = {
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

}
