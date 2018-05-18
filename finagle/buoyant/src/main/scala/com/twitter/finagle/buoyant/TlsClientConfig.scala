package com.twitter.finagle.buoyant

import java.io._
import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.ssl.client.Netty4ClientEngineFactory
import com.twitter.finagle.ssl.{KeyCredentials, Protocols, TrustCredentials}
import com.twitter.finagle.ssl.client.{SslClientConfiguration, SslClientEngineFactory}
import com.twitter.finagle.transport.Transport

import scala.util.control.NoStackTrace

case class TlsClientConfig(
  enabled: Option[Boolean],
  disableValidation: Option[Boolean],
  commonName: Option[String],
  trustCerts: Option[String] = None,
  clientAuth: Option[ClientAuth] = None,
  protocols: Option[Seq[String]] = None
) {
  def params: Stack.Params = this match {
    case TlsClientConfig(Some(false), _, _, _, _, _) =>
      Stack.Params.empty + Transport.ClientSsl(None)
    case TlsClientConfig(_, Some(true), _, _, clientAuth, enabledProtocols) =>
      val tlsConfig = SslClientConfiguration(
        trustCredentials = TrustCredentials.Insecure,
        keyCredentials = keyCredentials(clientAuth),
        protocols = enabledProtocols.map(Protocols.Enabled).getOrElse(Protocols.Unspecified)
      )
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(_, _, Some(cn), certs, clientAuth, enabledProtocols) =>
      val tlsConfig = SslClientConfiguration(
        hostname = Some(cn),
        trustCredentials = certs.map(c => TrustCredentials.CertCollection(new File(c))).getOrElse(TrustCredentials.Unspecified),
        keyCredentials = keyCredentials(clientAuth),
        protocols = enabledProtocols.map(Protocols.Enabled).getOrElse(Protocols.Unspecified)
      )
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(_, Some(false) | None, None, _, _, _) =>
      val msg = "tls is configured with validation but `commonName` is not set"
      throw new IllegalArgumentException(msg) with NoStackTrace
  }

  private[this] def keyCredentials(clientAuth: Option[ClientAuth]): KeyCredentials =
    clientAuth match {
      case Some(ClientAuth(cert, key)) => KeyCredentials.CertAndKey(new File(cert), new File(key))
      case None => KeyCredentials.Unspecified
    }
}

case class ClientAuth(certPath: String, keyPath: String)
