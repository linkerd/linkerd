package io.buoyant.k8s.istio

import com.twitter.finagle.Path
import istio.proxy.v1.config.{HTTPRewrite, MatchCondition, RouteRule, StringMatch}
import istio.proxy.v1.config.StringMatch.OneofMatchType

trait IdentifierPreconditions {
  def headerMatches(headerValue: String, stringMatch: StringMatch): Boolean = {
    stringMatch.`matchType` match {
      case Some(OneofMatchType.Exact(value)) => headerValue == value
      case Some(OneofMatchType.Prefix(pfx)) => headerValue.startsWith(pfx)
      case Some(OneofMatchType.Regex(r)) => headerValue.matches(r)
      case None => throw new IllegalArgumentException("stringMatch missing matchType")
    }
  }

  def pfx: Path
  def externalRequestPath(host: String): Path = {
    host.split(":") match {
      case Array(h: String, p: String) => pfx ++ Path.Utf8("ext", h, p)
      case Array(h: String) => pfx ++ Path.Utf8("ext", h, "80")
      case _ => throw new IllegalArgumentException("unable to parse host for request")
    }
  }

  def matchesAllConditions(getHeader: (String) => Option[String], matchCondition: MatchCondition): Boolean = {
    val matchesHeaders = matchCondition.`httpHeaders`.forall {
      case (headerName, stringMatch) =>
        // return false if the headerName does not appear in the request's headerMap
        getHeader(headerName) match {
          case Some(headerValue) => headerMatches(headerValue, stringMatch)
          case None => false
        }
    }
    //TODO: add other match conditions
    matchesHeaders
  }

  def httpRewrite(rule: RouteRule, uri: String, authority: Option[String]): (String, Option[String]) = {
    rule.`rewrite` match {
      case Some(HTTPRewrite(url, updatedAuth)) =>
        val updatedUri = url.map { replacement =>
          rule.`match`.flatMap(_.`httpHeaders`.get("uri").flatMap(_.`matchType`)) match {
            case Some(OneofMatchType.Prefix(pfx)) =>
              uri.replace(pfx, replacement)
            case _ =>
              uri.split("/").head + replacement
          }
        }
        (updatedUri.getOrElse(uri), updatedAuth.orElse(authority))
      case _ => (uri, authority)
    }
  }

  def filterRules(rules: Map[String, RouteRule], dest: String, getHeader: (String) => Option[String]): Seq[(String, RouteRule)] = rules.filter {
    case (_, r) if r.`destination`.contains(dest) =>
      // return true if no match conditions were defined on the route-rule
      r.`match`.forall(matchesAllConditions(getHeader, _))
    case _ => false
  }.toSeq

  def maxPrecedenceRuleName(rules: Seq[(String, RouteRule)]): Option[(String, RouteRule)] = {
    if (rules.isEmpty) {
      None
    } else {
      val rule = rules.maxBy[Int] { case (m: String, d: RouteRule) => d.`precedence`.getOrElse(0) }
      Some(rule)
    }
  }
}
