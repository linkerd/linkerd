package io.buoyant.http

import com.twitter.finagle.{Path, Service, ServiceNamer}
import com.twitter.finagle.http._
import com.twitter.util.{Future, Try}

/**
 * A service namer that accepts names in the form:
 *
 *   /400/resource/name
 *
 * and binds the name to an Http service that always responds with the
 * given status code (i.e. 400).
 */
class status extends ServiceNamer[Request, Response] {

  private[this] object Code {
    def unapply(s: String): Option[Status] =
      Try(s.toInt).toOption.filter { s => 100 <= s && s < 600 }.map(Status.fromCode(_))
  }

  def lookupService(path: Path): Option[Service[Request, Response]] = path.take(1) match {
    case Path.Utf8(Code(status)) => Some(Service.const(Future.value(Response(status))))
    case _ => None
  }
}
