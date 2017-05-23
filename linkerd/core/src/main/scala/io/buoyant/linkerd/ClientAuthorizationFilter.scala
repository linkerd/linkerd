package io.buoyant.linkerd

import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Filter, Service, SimpleFilter}
import com.twitter.util.Future
import io.buoyant.router.context.DstBoundCtx
import java.security.cert.X509Certificate
import scala.util.control.NoStackTrace

class ClientAuthorizationFilter[Req, Rep](
  allowUnauthenticated: Boolean,
  allowAllAuthenticated: Boolean,
  allow: Seq[String],
  deny: Seq[String]
) extends SimpleFilter[Req, Rep] {
  override def apply(
    request: Req,
    service: Service[Req, Rep]
  ): Future[Rep] = {
    val cn = Transport.peerCertificate.collect {
      case x509: X509Certificate => x509.getSubjectX500Principal.getName
    }
    cn match {
      case Some(name) =>
        if (isAllowed(name)) {
          service(request)
        } else {
          reject(Some(name))
        }
      case None =>
        if (allowUnauthenticated)
          service(request)
        else
          reject(None)
    }
  }

  private[this] def isAllowed(name: String): Boolean = {
    if (deny.exists(matches(name, _))) {
      false
    } else if (allow.exists(matches(name, _))) {
      true
    } else {
      allowAllAuthenticated
    }
  }

  private[this] def matches(name: String, pattern: String): Boolean = {
    val nameSegments = name.split(".")
    val patternSegments = pattern.split(".")
    if (nameSegments.size == patternSegments.size) {
      nameSegments.zip(patternSegments).forall { case (nameSegment, patternSegment) =>
        patternSegment == "*" || nameSegment == patternSegment
      }
    } else {
      false
    }
  }

  private[this] def reject(name: Option[String]): Future[Nothing] = {
    val clientDst = DstBoundCtx.current.map(_.idStr).getOrElse("???")
    val source = name.getOrElse("unauthenticated")
    Future.exception(new ClientAuthorizationException(s"$source is not authorized to send to $clientDst"))
  }
}

class ClientAuthorizationException(message: String) extends Throwable(message) with NoStackTrace
