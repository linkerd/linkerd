package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future

import scala.util.matching.Regex

object SecurityFilter {
  def apply(whitelist: Seq[Regex] = List.empty): SecurityFilter = new SecurityFilter(whitelist)

  private[SecurityFilter] val UiWhitelist = Seq(
    "/",
    "/.*[.]css",
    "/.*[.]svg",
    "/.*[.]png",
    "/.*[.]js",
    "/.*[.]woff2",
    "/config.json",
    "/metrics.json",
    "/admin/metrics.json",
    "/admin/metrics",
    "/delegator",
    "/delegator.json",
    "/logging",
    "/logging.json",
    "/help"
  ).map(s => s"^$s$$".r)
}

class SecurityFilter(val whitelist: Seq[Regex] = List.empty) extends SimpleFilter[Request, Response] {

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    whitelist.find(whitelistedUri => whitelistedUri.pattern.matcher(request.path).matches()) match {
      case Some(_) =>
        service(request)
      case None =>
        val rep = Response(request)
        rep.status = Status.NotFound
        Future.value(rep)
    }
  }

  def withUiEndpoints(): SecurityFilter =
    SecurityFilter(whitelist ++ SecurityFilter.UiWhitelist)

  def withWhitelistedElement(whitelistedRegex: String): SecurityFilter =
    SecurityFilter(whitelist :+ whitelistedRegex.r)
}
