package io.buoyant.router.http

import com.twitter.finagle._
import com.twitter.finagle.transport.Transport
import com.twitter.util.Future
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.xml.bind.DatatypeConverter.printHexBinary
import scala.collection.mutable

class ForwardClientCertFilter[Req, H: HeadersLike, Rep](implicit requestLike: RequestLike[Req, H]) extends SimpleFilter[Req, Rep] {

  val GeneralNameTypeUri = 6
  val GeneralNameTypeDns = 2

  val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

  def apply(req: Req, svc: Service[Req, Rep]): Future[Rep] = {
    Transport.peerCertificate.foreach(clientCert => {
      val clientCertHeader = new mutable.StringBuilder(128)

      clientCertHeader ++= s"Hash=${printHexBinary(digest.digest(clientCert.getEncoded))}"

      clientCert match {
        case x509ClientCert: X509Certificate =>
          val altNames = x509ClientCert.getSubjectAlternativeNames
          if (altNames != null && altNames.size > 0) {
            val it = altNames.iterator
            while (it.hasNext) {
              val altName = it.next
              val nameType = altName.get(0)
              val nameValue = altName.get(1)
              nameType match {
                case GeneralNameTypeUri => clientCertHeader ++= s";SAN=$nameValue"
                case GeneralNameTypeDns => clientCertHeader ++= s";DNS=$nameValue"
              }
            }
          }

          val subject = x509ClientCert.getSubjectX500Principal.getName
          if (subject.nonEmpty) {
            clientCertHeader ++= s""";Subject="$subject""""
          }
        case _ =>
      }

      val headersLike = implicitly[HeadersLike[H]]
      headersLike.set(requestLike.headers(req), "x-forwarded-client-cert", clientCertHeader.result)
    })
    svc(req)
  }
}

object ForwardClientCertFilter {

  case class Enabled(enabled: Boolean)
  implicit object Param extends Stack.Param[Enabled] {
    val default = Enabled(false)
  }

  val role = Stack.Role("ForwardClientCertFilter")

  def module[Req, H: HeadersLike, Rep](implicit requestLike: RequestLike[Req, H]): Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module1[Enabled, ServiceFactory[Req, Rep]] {
      val role = ForwardClientCertFilter.role
      val description = "Adds a 'x-forwarded-client-cert' header to requests as they are received"
      def make(_param: Enabled, next: ServiceFactory[Req, Rep]) = {
        if (_param.enabled) new ForwardClientCertFilter().andThen(next)
        else next
      }
    }
}

