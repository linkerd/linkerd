package io.buoyant.admin

import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Request, Response, Status}
import com.twitter.util.Future
import scala.util.matching.Regex

object Category {
  val categories = Seq(UiCategory, ControlCategory)
}

sealed trait Category {
  def urls: Seq[Regex]

  def contains(path: String): Boolean = urls.exists(_.pattern.matcher(path).matches())
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
}

object ControlCategory extends Category {
  val urls: Seq[Regex] = Seq(
    "/admin/shutdown",
    "/logging[.]json"
  ).map(s => s"^$s$$".r)
}

object DiagnosticsCategory extends Category {
  val urls: Seq[Regex] = Seq.empty

  override def contains(path: String): Boolean = true
}

case class SecurityFilter(
  uiEnabled: Boolean = true,
  controlEnabled: Boolean = true,
  diagnosticsEnabled: Boolean = true,
  whitelist: Seq[Regex] = List.empty,
  blacklist: Seq[Regex] = List.empty
) extends SimpleFilter[Request, Response] {
  import Category._

  override def apply(request: Request, service: Service[Request, Response]): Future[Response] = {
    val category = categories.find(_.contains(request.path)).getOrElse(DiagnosticsCategory)
    val categoryEnabled = category match {
      case UiCategory => uiEnabled
      case ControlCategory => controlEnabled
      case DiagnosticsCategory => diagnosticsEnabled
    }

    if (isRejectedByBlacklist(request)) {
      reject(request)
    } else if (categoryEnabled || isAllowedByWhiteList(request)) {
      service(request)
    } else {
      reject(request)
    }
  }

  private[this] def isAllowedByWhiteList(request: Request) = whitelist.exists(whitelistedUri => whitelistedUri.pattern.matcher(request.path).matches())

  private[this] def isRejectedByBlacklist(request: Request) = blacklist.exists(blacklistedUri => blacklistedUri.pattern.matcher(request.path).matches())

  private[this] def reject(request: Request): Future[Response] = {
    val rep = Response(request)
    rep.status = Status.NotFound
    Future.value(rep)
  }
}
