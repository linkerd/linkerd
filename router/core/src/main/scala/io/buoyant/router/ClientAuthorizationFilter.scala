package io.buoyant.router

import com.twitter.finagle._
import com.twitter.finagle.transport.Transport
import com.twitter.util.Future
import java.security.cert.X509Certificate
import scala.util.control.NoStackTrace

object ClientAuthorizationFilter {

  val role = Stack.Role("ClientAuthorization")

  case class Param(
    allowUnauthenticated: Boolean,
    allowAllAuthenticated: Boolean,
    allow: Seq[String],
    deny: Seq[String]
  )
  implicit object Param extends Stack.Param[Param] {
    override def default = Param(
      allowUnauthenticated = true,
      allowAllAuthenticated = true,
      allow = Nil,
      deny = Nil
    )
  }

  def module[Req, Rep] = new Stack.Module2[Param, param.Label, ServiceFactory[Req, Rep]] {

    override def role: Stack.Role = ClientAuthorizationFilter.role
    override def description: String = "insert description here"

    override def make(
      p: Param,
      dst: param.Label,
      next: ServiceFactory[Req, Rep]
    ): ServiceFactory[Req, Rep] = {
      val filter = if (p == Param.default)
        Filter.identity[Req, Rep]
      else
        new ClientAuthorizationFilter[Req, Rep](
          dst.label,
          p.allowUnauthenticated,
          p.allowAllAuthenticated,
          p.allow,
          p.deny
        )
      filter.andThen(next)
    }
  }
}

class ClientAuthorizationFilter[Req, Rep](
  dst: String,
  allowUnauthenticated: Boolean,
  allowAllAuthenticated: Boolean,
  allow: Seq[String],
  deny: Seq[String]
) extends SimpleFilter[Req, Rep] {

  val cnRegex = """.*CN=([^,]*).*""".r

  override def apply(
    request: Req,
    service: Service[Req, Rep]
  ): Future[Rep] = {
    val commonName = Transport.peerCertificate.collect {
      case x509: X509Certificate => x509.getSubjectX500Principal.getName
    }.collect {
      case cnRegex(cn) => cn
    }
    commonName match {
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
      nameSegments.zip(patternSegments).forall {
        case (nameSegment, patternSegment) =>
          patternSegment == "*" || nameSegment == patternSegment
      }
    } else {
      false
    }
  }

  private[this] def reject(name: Option[String]): Future[Nothing] = {
    val source = name.getOrElse("unauthenticated")
    Future.exception(new ClientAuthorizationException(s"$source is not authorized to send to $dst"))
  }
}

class ClientAuthorizationException(message: String) extends Throwable(message) with NoStackTrace
