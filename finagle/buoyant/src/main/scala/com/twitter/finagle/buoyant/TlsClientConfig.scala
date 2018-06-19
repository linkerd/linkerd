package com.twitter.finagle.buoyant

import java.io._
import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.ssl.client.Netty4ClientEngineFactory
import com.twitter.finagle.ssl.{KeyCredentials, Protocols, TrustCredentials}
import com.twitter.finagle.ssl.client.{SslClientConfiguration, SslClientEngineFactory}
import com.twitter.finagle.transport.Transport
import com.twitter.io.StreamIO
import scala.util.control.NoStackTrace

case class TlsClientConfig(
  enabled: Option[Boolean],
  disableValidation: Option[Boolean],
  commonName: Option[String],
  trustCerts: Option[Seq[String]] = None,
  trustCertsBundle: Option[String] = None,
  clientAuth: Option[ClientAuth] = None,
  protocols: Option[Seq[String]] = None
) {
  def params: Stack.Params = this match {
    case TlsClientConfig(Some(false), _, _, _, _, _, _) =>
      Stack.Params.empty + Transport.ClientSsl(None)
    case TlsClientConfig(_, Some(true), _, _, _, clientAuth, enabledProtocols) =>
      val tlsConfig = SslClientConfiguration(
        trustCredentials = TrustCredentials.Insecure,
        keyCredentials = keyCredentials(clientAuth),
        protocols = enabledProtocols.map(Protocols.Enabled).getOrElse(Protocols.Unspecified)
      )
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(_, _, Some(cn), Some(certs), Some(certsBundle), _, _) =>
      val msg = "Both trustCerts and trustCertsBundle have been set. Please use only trustCertsBundle."
      throw new IllegalArgumentException(msg) with NoStackTrace

    case TlsClientConfig(_, _, Some(cn), certs, None, clientAuth, enabledProtocols) =>
      // map over the optional certs parameter - we want to pass
      // `TrustCredentials.CertCollection` if we were given a list of certs,
      // but `TrustCredentials.Unspecified` (rather than an empty cert
      // collection file) if we were not.
      val credentials = certs.map { certs =>
        // a temporary file to hold the collection of certificates
        val certCollection = File.createTempFile("certCollection", null)
        // open the cert paths as Streams...
        val f = new FileOutputStream(certCollection)
        for {
          cert <- certs
          certStream = new FileInputStream(cert)
        } { // ...and copy the certs into the cert collection
          // TODO: can this be made more concise with scala.io?
          StreamIO.copy(certStream, f)
        }
        f.flush()
        f.close()
        certCollection.deleteOnExit()
        // the credentials we'll pass to `SslClientConfiguration` will
        // be a collection of certificates
        TrustCredentials.CertCollection(certCollection)
      } getOrElse {
        // otherwise, we want to pass `TrustCredentials.Unspecified`
        TrustCredentials.Unspecified
      }

      val tlsConfig = SslClientConfiguration(
        hostname = Some(cn),
        trustCredentials = credentials,
        keyCredentials = keyCredentials(clientAuth),
        protocols = enabledProtocols.map(Protocols.Enabled).getOrElse(Protocols.Unspecified)
      )
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(_, _, Some(cn), None, Some(certsBundle), clientAuth, enabledProtocols) =>
      val credentials = TrustCredentials.CertCollection(new File(certsBundle))

      val tlsConfig = SslClientConfiguration(
        hostname = Some(cn),
        trustCredentials = credentials,
        keyCredentials = keyCredentials(clientAuth),
        protocols = enabledProtocols.map(Protocols.Enabled).getOrElse(Protocols.Unspecified)
      )
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(_, Some(false) | None, None, _, _, _, _) =>
      val msg = "tls is configured with validation but `commonName` is not set"
      throw new IllegalArgumentException(msg) with NoStackTrace
  }

  private[this] def keyCredentials(clientAuth: Option[ClientAuth]): KeyCredentials =
    clientAuth match {
      case Some(ClientAuth(cert, None, key)) =>
        KeyCredentials.CertAndKey(new File(cert), new File(key))
      case Some(ClientAuth(cert, Some(intermediate), key)) =>
        KeyCredentials.CertKeyAndChain(new File(cert), new File(key), new File(intermediate))
      case None => KeyCredentials.Unspecified
    }
}

case class ClientAuth(
  certPath: String,
  intermediateCertsPath: Option[String],
  keyPath: String
)
