package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.factory.BindingFactory
import com.twitter.finagle.netty3._
import com.twitter.finagle.ssl.{Ssl, Engine}
import com.twitter.finagle.transport.{TlsConfig, Transport}
import java.io.FileInputStream
import java.net.{InetSocketAddress, SocketAddress}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.{SSLContext, TrustManagerFactory}

object TlsClientPrep {
  val role = Stack.Role("TlsClientPrep")

  val description = "Configures per-endpoint TLS settings"

  /** A placeholder stack module that does no TLS configuration. */
  def nop[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Stack.Module0[ServiceFactory[Req, Rsp]] {
      val role = TlsClientPrep.role
      val description = TlsClientPrep.description
      def make(next: ServiceFactory[Req, Rsp]) = next
    }

  private[this] def sslContext(caCert: String): SSLContext = {
    // Establish an SSL context that uses the provided caCert
    // Cribbed from http://stackoverflow.com/questions/18513792
    val cf = CertificateFactory.getInstance("X.509")
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    val ks = KeyStore.getInstance(KeyStore.getDefaultType)
    ks.load(null)
    ks.setCertificateEntry("caCert", cf.generateCertificate(new FileInputStream(caCert)))
    tmf.init(ks)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, tmf.getTrustManagers, null)
    ctx
  }

  def addrEngine(commonName: String, caCert: Option[String])(addr: SocketAddress) = addr match {
    case addr: InetSocketAddress =>
      caCert match {
        case Some(cert) => Ssl.client(sslContext(cert), commonName, addr.getPort)
        case None => Ssl.client(commonName, addr.getPort)
      }
    case _ => Ssl.client()
  }

  def mkTlsConfig(commonName: String, caCert: Option[String]): TlsConfig = caCert match {
    case Some(cert) => TlsConfig.ClientSslContextAndHostname(sslContext(cert), commonName)
    case None => TlsConfig.ClientHostname(commonName)
  }

  /**
   * May be extended to implement a TlsClientPrep module. Supports
   * Params-driven TLS configuration.
   */
  trait Module[Req, Rsp] extends Stack.Module[ServiceFactory[Req, Rsp]] {
    val role = TlsClientPrep.role
    val description = TlsClientPrep.description

    /**
     * May return a function that builds an SSL engine.  If None is
     * returned, TLS is disabled.
     */
    def newEngine(cn: Option[String]): Option[SocketAddress => Engine]
    def tlsConfig(cn: Option[String]): TlsConfig

    /** May return a TLS commonName to identify the remote server. */
    def peerCommonName(params: Stack.Params): Option[String]

    def make(params: Stack.Params, next: Stack[ServiceFactory[Req, Rsp]]) = {
      val cn = peerCommonName(params)
      val tlsParams = newEngine(cn) match {
        case None =>
          // remove TLS from this connection.
          params + Transport.TLSClientEngine(None) + Transport.Tls(tlsConfig(cn))
        case Some(mkEngine) =>
          val cfg = new Netty3TransporterTLSConfig(mkEngine, cn)
          params +
            Transport.Tls(tlsConfig(cn)) +
            Transport.TLSClientEngine(Some(cfg.newEngine)) +
            Transporter.TLSHostname(cfg.verifyHost)
      }
      Stack.Leaf(role, next.make(tlsParams))
    }
  }

  /** A module that always disables TLS. */
  def disable[Req, Rsp]: Module[Req, Rsp] =
    new Module[Req, Rsp] {
      val parameters = Seq.empty
      def newEngine(_cn: Option[String]) = None
      def tlsConfig(_cn: Option[String]) = TlsConfig.Disabled
      def peerCommonName(params: Stack.Params) = None
    }

  /** A module that uses the given `commonName` for all requests. */
  def static[Req, Rsp](commonName: String, caCert: Option[String]): Module[Req, Rsp] =
    new Module[Req, Rsp] {
      val parameters = Seq.empty
      def newEngine(_cn: Option[String]) = Some(addrEngine(commonName, caCert))
      def tlsConfig(_cn: Option[String]) = mkTlsConfig(commonName, caCert)
      def peerCommonName(params: Stack.Params) = Some(commonName)
    }

  /** A module that configures TLS to ignore certificate validation. */
  def withoutCertificateValidation[Req, Rsp]: Module[Req, Rsp] =
    new Module[Req, Rsp] {
      val parameters = Seq.empty
      def newEngine(_cn: Option[String]) = Some(addrEngine)
      def tlsConfig(_cn: Option[String]) = TlsConfig.ClientNoValidation
      def peerCommonName(params: Stack.Params) = None
      private[this] def addrEngine(addr: SocketAddress) = addr match {
        case addr: InetSocketAddress =>
          Ssl.clientWithoutCertificateValidation(addr.getHostName, addr.getPort)
        case _ => Ssl.clientWithoutCertificateValidation()
      }
    }
}
