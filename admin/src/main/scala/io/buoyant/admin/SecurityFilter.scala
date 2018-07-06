package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future

import scala.util.matching.Regex

object SecurityFilter {
  def apply(whitelist: Seq[Regex] = List.empty, blacklist: Seq[Regex] = List.empty): SecurityFilter = new SecurityFilter(whitelist, blacklist)

  private[SecurityFilter] val UiWhitelist = Seq(
    "/",
    "/files/.*",
    "/admin/files/.*",
    "/config[.]json",
    "/metrics[.]json",
    "/admin/metrics[.]json",
    "/admin/metrics",
    "/delegator",
    "/delegator[.]json",
    "/logging",
    "/help"
  ).map(s => s"^$s$$".r)

  private[SecurityFilter] val ControlWhitelist = Seq(
    "/admin/shutdown",
    "/logging[.]json"
  ).map(s => s"^$s$$".r)

  private[SecurityFilter] val DiagnosticsWhitelist = Seq(
    ".*"
  ).map(s => s"^$s$$".r)
}

class SecurityFilter(
  val whitelist: Seq[Regex] = List.empty,
  val blacklist: Seq[Regex] = List.empty
) extends SimpleFilter[Request, Response] {

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] =
    if (!isRejected(request) && isAllowed(request)) {
      service(request)
    } else {
      reject(request)
    }

  private def isAllowed(request: Request) = whitelist.exists(whitelistedUri => whitelistedUri.pattern.matcher(request.path).matches())

  private def isRejected(request: Request) = blacklist.exists(blacklistedUri => blacklistedUri.pattern.matcher(request.path).matches())

  private def reject(request: Request): Future[Response] = {
    val rep = Response(request)
    rep.status = Status.NotFound
    Future.value(rep)
  }

  def withUiEndpoints(): SecurityFilter =
    SecurityFilter(whitelist ++ SecurityFilter.UiWhitelist, blacklist)

  def withControlEndpoints(): SecurityFilter =
    SecurityFilter(whitelist ++ SecurityFilter.ControlWhitelist, blacklist)

  def withDiagnosticsEndpoints(): SecurityFilter =
    SecurityFilter(whitelist ++ SecurityFilter.ControlWhitelist, blacklist)

  def withWhitelistedElement(whitelistedRegex: String): SecurityFilter =
    SecurityFilter(whitelist :+ whitelistedRegex.r, blacklist)

  def withBlacklistedElement(blacklistedRegex: String): SecurityFilter =
    SecurityFilter(whitelist, blacklist :+ blacklistedRegex.r)
}
