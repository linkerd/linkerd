package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.ssl.{Engine, Ssl}
import com.twitter.finagle.transport.{TlsConfig, Transport}
import java.io.FileInputStream
import java.net.{InetSocketAddress, SocketAddress}
import java.security.KeyStore
import java.security.cert.{CertificateFactory, X509Certificate}
import javax.net.ssl.{SSLContext, TrustManagerFactory}

object TlsClientPrep {

  object role extends Stack.Role("TlsClientPrep") {
    val finagle = Stack.Role("TlsClientPrep.Finagle")
  }

  val description = "Configures per-endpoint TLS settings"

  type Params = Stack.Params

  /**
   * Configures TLS protocol parameters, including whether TLS should
   * be used at all.
   */
  case class TransportSecurity(config: TransportSecurity.Config)
  implicit object TransportSecurity extends Stack.Param[TransportSecurity] {
    sealed trait Config

    /** TODO support Protocols, Ciphers, & timeouts */
    case class Secure() extends Config

    object Insecure extends Config

    val default = TransportSecurity(Insecure)
  }

  /**
   * Configures the endpoint trust model to use with remote servers.
   */
  case class Trust(config: Trust.Config)
  implicit object Trust extends Stack.Param[Trust] {
    sealed trait Config
    case class Verified(name: String, certs: Seq[X509Certificate]) extends Config
    object UnsafeNotVerified extends Config
    object NotConfigured extends Config

    val default = Trust(NotConfigured)
  }

  /**
   * May be extended to implement a TlsClientPrep module. Supports
   * Params-driven TLS configuration.
   */
  type Module[Req, Rsp] = Stack.Module[ServiceFactory[Req, Rsp]]
  type Stk[Req, Rsp] = Stack[ServiceFactory[Req, Rsp]]
  type Stkable[Req, Rsp] = Stackable[ServiceFactory[Req, Rsp]]

  /** A helper for building modules that conifgure TransportSecurity & Trust. */
  trait TlsTrustModule[Req, Rsp] extends Stack.Module[ServiceFactory[Req, Rsp]] {
    override val role = TlsClientPrep.role
    override val description = TlsClientPrep.description
    override val parameters = Nil

    def transportSecurity: TransportSecurity.Config
    def trust: Trust.Config

    def make(params: Stack.Params, next: Stk[Req, Rsp]): Stk[Req, Rsp] =
      Stack.Leaf(role, next.make(params + TransportSecurity(transportSecurity) + Trust(trust)))
  }

  /** A module that always disables TLS. */
  def insecure[Req, Rsp]: Stkable[Req, Rsp] =
    new TlsTrustModule[Req, Rsp] {
      override val transportSecurity = TransportSecurity.Insecure
      override val trust = Trust.NotConfigured
    }

  /**
   * A module that configures TransportSecurity and Trust to verify
   * that the remote's common name is `cn`.
   */
  def static[Req, Rsp](cn: String, trustCerts: Seq[String]): Stkable[Req, Rsp] =
    new TlsTrustModule[Req, Rsp] {
      override val transportSecurity = TransportSecurity.Secure()
      override val trust = Trust.Verified(cn, trustCerts.map(loadCert(_)))
    }

  def static[Req, Rsp](cn: String, trustCert: Option[String]): Stkable[Req, Rsp] =
    static(cn, trustCert.toSeq)

  /**
   * A module that configures TransportSecurity and Trust to NOT
   * verify that the remote's common name.
   */
  def withoutCertificateValidation[Req, Rsp]: Stkable[Req, Rsp] =
    new TlsTrustModule[Req, Rsp] {
      override val transportSecurity = TransportSecurity.Secure()
      override val trust = Trust.UnsafeNotVerified
    }

  /**
   * A module that uses the TransportSecurity and Trust params to
   * configure finagle-specific TLS configuration parameters like
   * `Transport.Tls`.
   *
   * Configures a java SSLContext context on the client, which may not
   * be suitable for all TLS configurations.
   */
  def configureFinagleTls[Req, Rsp]: Stkable[Req, Rsp] =
    new Stack.Module[ServiceFactory[Req, Rsp]] {
      val role = TlsClientPrep.role.finagle
      val description = TlsClientPrep.description
      val parameters = Seq(
        implicitly[Stack.Param[TransportSecurity]],
        implicitly[Stack.Param[Trust]]
      )

      def make(params: Stack.Params, next: Stk[Req, Rsp]) = {
        val tlsParams = params[TransportSecurity].config match {
          case TransportSecurity.Insecure =>
            params + Transport.Tls(TlsConfig.Disabled) +
              Transport.TLSClientEngine(None)

          case TransportSecurity.Secure() =>
            params[Trust].config match {
              case Trust.NotConfigured =>
                throw new IllegalArgumentException("no trust management policy configured for client TLS")

              case Trust.UnsafeNotVerified =>
                val engine: SocketAddress => Engine = {
                  case addr: InetSocketAddress =>
                    Ssl.clientWithoutCertificateValidation(addr.getHostName, addr.getPort)
                  case _ => Ssl.clientWithoutCertificateValidation()
                }
                params + Transport.Tls(TlsConfig.ClientNoValidation) +
                  Transport.TLSClientEngine(Some(engine))

              case Trust.Verified(cn, certs) =>
                val engine: SocketAddress => Engine = {
                  case addr: InetSocketAddress =>
                    certs match {
                      case Nil => Ssl.client(cn, addr.getPort)
                      case certs => Ssl.client(sslContext(certs), cn, addr.getPort)
                    }
                  case _: SocketAddress => Ssl.client()
                }
                val tlsConfig = certs match {
                  case Nil => TlsConfig.ClientHostname(cn)
                  case certs => TlsConfig.ClientSslContextAndHostname(sslContext(certs), cn)
                }
                params + Transport.Tls(tlsConfig) +
                  Transport.TLSClientEngine(Some(engine)) +
                  Transporter.TLSHostname(Some(cn))
            }
        }
        Stack.Leaf(role, next.make(tlsParams))
      }
    }

  /**
   * A module that always disable's Finagle's TLS configuration.
   *
   * Useful for protocol implementations that directly configure TLS
   * on the netty pipeline.
   */
  def disableFinagleTls[Req, Rsp]: Stkable[Req, Rsp] =
    new Stack.Module[ServiceFactory[Req, Rsp]] {
      val role = TlsClientPrep.role.finagle
      val description = TlsClientPrep.description
      val parameters = Nil
      def make(params0: Stack.Params, next: Stk[Req, Rsp]) = {
        val tlsParams = params0 +
          Transport.Tls(TlsConfig.Disabled) +
          Transport.TLSClientEngine(None)
        Stack.Leaf(role, next.make(tlsParams))
      }
    }

  private[this] lazy val X509 = CertificateFactory.getInstance("X.509")
  def loadCert(path: String): X509Certificate =
    X509.generateCertificate(new FileInputStream(path)) match {
      case c: X509Certificate => c
      case c => throw new IllegalArgumentException(s"invalid cert type: $c")
    }

  private[this] def sslContext(certs: Seq[X509Certificate]): SSLContext = {
    // Establish an SSL context that uses the provided caCert
    // Cribbed from http://stackoverflow.com/questions/18513792
    val ks = KeyStore.getInstance(KeyStore.getDefaultType)
    ks.load(null)
    certs.map { cert =>
      ks.setCertificateEntry(cert.getSubjectX500Principal.getName, cert)
    }

    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, null)

    ctx
  }

}
