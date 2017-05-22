package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.netty4.ssl.client.Netty4ClientEngineFactory
import com.twitter.finagle.ssl.client.{SslClientConfiguration, SslClientEngineFactory}
import com.twitter.finagle.ssl.{KeyCredentials, TrustCredentials}
import com.twitter.finagle.transport.Transport
import com.twitter.io.{StreamIO, TempDirectory, TempFile}
import java.io.{File, FileInputStream, FileOutputStream}

object TlsClientPrep {

  object role extends Stack.Role("TlsClientPrep") {
    val finagle = Stack.Role("TlsClientPrep.Finagle")
  }

  val description = "Configures per-endpoint TLS settings"

  type Params = Stack.Params

  case class ClientAuth(cert: File, key: File)

  /**
   * Configures TLS protocol parameters, including whether TLS should
   * be used at all.
   */
  case class TransportSecurity(config: TransportSecurity.Config)
  implicit object TransportSecurity extends Stack.Param[TransportSecurity] {
    sealed trait Config

    /** TODO support Protocols, Ciphers, & timeouts */
    case class Secure(clientAuth: Option[ClientAuth] = None) extends Config

    object Insecure extends Config

    val default = TransportSecurity(Insecure)
  }

  /**
   * Configures the endpoint trust model to use with remote servers.
   */
  case class Trust(config: Trust.Config)
  implicit object Trust extends Stack.Param[Trust] {
    sealed trait Config
    case class Verified(name: String, certPaths: Seq[String]) extends Config
    case object UnsafeNotVerified extends Config
    case object NotConfigured extends Config

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
      override val trust = Trust.Verified(cn, trustCerts)
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
            params + Transport.ClientSsl(None)

          case TransportSecurity.Secure(clientAuth) =>
            params[Trust].config match {
              case Trust.NotConfigured =>
                throw new IllegalArgumentException("no trust management policy configured for client TLS")

              case Trust.UnsafeNotVerified =>
                val tlsConfig = SslClientConfiguration(
                  trustCredentials = TrustCredentials.Insecure,
                  keyCredentials = keyCredentials(clientAuth)
                )
                params + Transport.ClientSsl(Some(tlsConfig)) +
                  SslClientEngineFactory.Param(Netty4ClientEngineFactory())

              case Trust.Verified(cn, trustCerts) =>
                val trustStreams = trustCerts.map(new FileInputStream(_))
                val certCollection = File.createTempFile("certCollection", null)
                val f = new FileOutputStream(certCollection)
                for (cert <- trustStreams) StreamIO.copy(cert, f)
                f.flush()
                f.close()
                certCollection.deleteOnExit()

                val tlsConfig = SslClientConfiguration(
                  hostname = Some(cn),
                  trustCredentials = TrustCredentials.CertCollection(certCollection),
                  keyCredentials = keyCredentials(clientAuth)
                )
                params + Transport.ClientSsl(Some(tlsConfig)) +
                  SslClientEngineFactory.Param(Netty4ClientEngineFactory())
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
          Transport.ClientSsl(None)
        Stack.Leaf(role, next.make(tlsParams))
      }
    }

  private[this] def keyCredentials(clientAuth: Option[ClientAuth]): KeyCredentials =
    clientAuth match {
      case Some(ClientAuth(cert, key)) => KeyCredentials.CertAndKey(cert, key)
      case None => KeyCredentials.Unspecified
    }
}
