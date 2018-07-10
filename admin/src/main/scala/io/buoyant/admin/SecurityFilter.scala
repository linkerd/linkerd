package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future
import scala.util.matching.Regex

object SecurityFilter {
  def apply(
    uiEnabled: Boolean = true,
    controlEnabled: Boolean = true,
    diagnosticsEnabled: Boolean = true,
    whitelist: Seq[Regex] = List.empty,
    blacklist: Seq[Regex] = List.empty
  ): SecurityFilter =
    new SecurityFilter(uiEnabled, controlEnabled, diagnosticsEnabled, whitelist, blacklist)
}

object Category {
  val categories = Seq(UiCategory, ControlCategory)
}

sealed trait Category {
  def urls: Seq[Regex]

  def contains(path: String): Boolean = urls.exists(_.pattern.matcher(path).matches())

  def isEnabled(securityFilter: SecurityFilter): Boolean
}

object UiCategory extends Category {
  val urls: Seq[Regex] = Seq(
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

  override def isEnabled(securityFilter: SecurityFilter): Boolean = securityFilter.uiEnabled
}

object ControlCategory extends Category {
  val urls: Seq[Regex] = Seq(
    "/admin/shutdown",
    "/logging[.]json"
  ).map(s => s"^$s$$".r)

  override def isEnabled(securityFilter: SecurityFilter): Boolean = securityFilter.controlEnabled
}

object DiagnosticsCategory extends Category {
  val urls: Seq[Regex] = Seq.empty

  override def contains(path: String): Boolean = true

  override def isEnabled(securityFilter: SecurityFilter): Boolean = securityFilter.diagnosticsEnabled
}

class SecurityFilter(
  val uiEnabled: Boolean,
  val controlEnabled: Boolean,
  val diagnosticsEnabled: Boolean,
  val whitelist: Seq[Regex] = List.empty,
  val blacklist: Seq[Regex] = List.empty
) extends SimpleFilter[Request, Response] {
  import Category._

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    val category = categories.find(_.contains(request.path)).getOrElse(DiagnosticsCategory)
    val categoryEnabled = category.isEnabled(this)

    if (categoryEnabled && !isRejectedByBlacklist(request)
      || !categoryEnabled && isAllowedByWhiteList(request)) {
      service(request)
    } else {
      reject(request)
    }
  }

  private def isAllowedByWhiteList(request: Request) = whitelist.exists(whitelistedUri => whitelistedUri.pattern.matcher(request.path).matches())

  private def isRejectedByBlacklist(request: Request) = blacklist.exists(blacklistedUri => blacklistedUri.pattern.matcher(request.path).matches())

  private def reject(request: Request): Future[Response] = {
    val rep = Response(request)
    rep.status = Status.NotFound
    Future.value(rep)
  }

  def withUiEndpoints(uiEnabled: Boolean = true): SecurityFilter =
    SecurityFilter(uiEnabled = uiEnabled, controlEnabled, diagnosticsEnabled, whitelist, blacklist)

  def withControlEndpoints(controlEnabled: Boolean = true): SecurityFilter =
    SecurityFilter(uiEnabled, controlEnabled = controlEnabled, diagnosticsEnabled, whitelist, blacklist)

  def withDiagnosticsEndpoints(diagnosticsEnabled: Boolean = true): SecurityFilter =
    SecurityFilter(uiEnabled, controlEnabled, diagnosticsEnabled = diagnosticsEnabled, whitelist, blacklist)

  def withWhitelistedElement(whitelistedRegex: String): SecurityFilter =
    SecurityFilter(uiEnabled, controlEnabled, diagnosticsEnabled, whitelist :+ whitelistedRegex.r, blacklist)

  def withBlacklistedElement(blacklistedRegex: String): SecurityFilter =
    SecurityFilter(uiEnabled, controlEnabled, diagnosticsEnabled, whitelist, blacklist :+ blacklistedRegex.r)

  override def toString = s"SecurityFilter(uiEnabled=$uiEnabled, controlEnabled=$controlEnabled, diagnosticsEnabled=$diagnosticsEnabled, whitelist=$whitelist, blacklist=$blacklist)"
}
