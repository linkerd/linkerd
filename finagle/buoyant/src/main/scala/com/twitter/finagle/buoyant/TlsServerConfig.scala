package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack
import com.twitter.finagle.ssl.{ClientAuth => FClientAuth, _}
import com.twitter.finagle.ssl.server.{SslServerConfiguration, SslServerEngineFactory}
import com.twitter.finagle.transport.Transport
import java.io.File

case class TlsServerConfig(
  certPath: String,
  keyPath: String,
  caCertPath: Option[String] = None,
  ciphers: Option[Seq[String]] = None,
  requireClientAuth: Option[Boolean] = None
) {
  def params(
    alpnProtocols: Option[Seq[String]],
    sslServerEngine: SslServerEngineFactory
  ): Stack.Params = {
    val trust = caCertPath match {
      case Some(caCertPath) => TrustCredentials.CertCollection(new File(caCertPath))
      case None => TrustCredentials.Unspecified
    }
    val cipherSuites = ciphers match {
      case Some(cs) => CipherSuites.Enabled(cs)
      case None => CipherSuites.Unspecified
    }
    val appProtocols = alpnProtocols match {
      case Some(ps) => ApplicationProtocols.Supported(ps)
      case None => ApplicationProtocols.Unspecified
    }
    val clientAuth = requireClientAuth match {
      case Some(true) => FClientAuth.Needed
      case _ => FClientAuth.Off
    }

    Stack.Params.empty + Transport.ServerSsl(Some(SslServerConfiguration(
      clientAuth = clientAuth,
      keyCredentials = KeyCredentials.CertAndKey(new File(certPath), new File(keyPath)),
      trustCredentials = trust,
      cipherSuites = cipherSuites,
      applicationProtocols = appProtocols
    ))) + SslServerEngineFactory.Param(sslServerEngine)
  }
}
