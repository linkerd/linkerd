package com.twitter.finagle.buoyant

import com.twitter.finagle._
import com.twitter.finagle.client.Transporter
import com.twitter.finagle.factory.BindingFactory
import com.twitter.finagle.netty3._
import com.twitter.finagle.ssl.{Ssl, Engine}
import com.twitter.finagle.transport.Transport
import java.net.{InetSocketAddress, SocketAddress}

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
    def newEngine(params: Stack.Params): Option[SocketAddress => Engine]

    /** May return a TLS commonName to identify the remote server. */
    def peerCommonName(params: Stack.Params): Option[String]

    def make(params: Stack.Params, next: Stack[ServiceFactory[Req, Rsp]]) = {
      val tlsParams = newEngine(params) match {
        case None =>
          // remove TLS from this connection.
          params + Transport.TLSClientEngine(None)
        case Some(mkEngine) =>
          val cfg = new Netty3TransporterTLSConfig(mkEngine, peerCommonName(params))
          params +
            Transport.TLSClientEngine(Some(cfg.newEngine)) +
            Transporter.TLSHostname(cfg.verifyHost)
      }
      Stack.Leaf(role, next.make(tlsParams))
    }
  }

  /** A module that always disables TLS. */
  def disable[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Module[Req, Rsp] {
      val parameters = Seq.empty
      def newEngine(params: Stack.Params) = None
      def peerCommonName(params: Stack.Params) = None
    }

  /** A module that uses the given `commonName` for all requests. */
  def static[Req, Rsp](commonName: String): Stackable[ServiceFactory[Req, Rsp]] =
    new Module[Req, Rsp] {
      val parameters = Seq.empty
      def newEngine(params: Stack.Params) = Some(addrEngine)
      def peerCommonName(params: Stack.Params) = Some(commonName)
      private[this] def addrEngine(addr: SocketAddress) = addr match {
        case addr: InetSocketAddress => Ssl.client(commonName, addr.getPort)
        case _ => Ssl.client()
      }
    }

  /** A module that configures TLS to ignore certificate validation. */
  def withoutCertificateValidation[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
    new Module[Req, Rsp] {
      val parameters = Seq.empty
      def newEngine(params: Stack.Params) = Some(addrEngine)
      def peerCommonName(params: Stack.Params) = None
      private[this] def addrEngine(addr: SocketAddress) = addr match {
        case addr: InetSocketAddress =>
          Ssl.clientWithoutCertificateValidation(addr.getHostName, addr.getPort)
        case _ => Ssl.clientWithoutCertificateValidation()
      }
    }
}
