package com.twitter.finagle.buoyant

import com.twitter.finagle.Stack
import com.twitter.finagle.netty4.ssl.client.Netty4ClientEngineFactory
import com.twitter.finagle.ssl.{KeyCredentials, TrustCredentials}
import com.twitter.finagle.ssl.client.{SslClientConfiguration, SslClientEngineFactory}
import com.twitter.finagle.transport.Transport
import com.twitter.io.StreamIO
import java.io.{File, FileInputStream, FileOutputStream}
import scala.util.control.NoStackTrace

case class TlsClientConfig(
  disableValidation: Option[Boolean],
  commonName: Option[String],
  trustCerts: Option[Seq[String]] = None,
  clientAuth: Option[ClientAuth] = None
) {
  def params: Stack.Params = this match {
    case TlsClientConfig(Some(true), _, _, clientAuth) =>
      val tlsConfig = SslClientConfiguration(
        trustCredentials = TrustCredentials.Insecure,
        keyCredentials = keyCredentials(clientAuth)
      )
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(_, Some(cn), certs, clientAuth) =>
      val trustStreams = certs.getOrElse(Nil).map(new FileInputStream(_))
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
      Stack.Params.empty + Transport.ClientSsl(Some(tlsConfig)) +
        SslClientEngineFactory.Param(Netty4ClientEngineFactory())

    case TlsClientConfig(Some(false) | None, None, _, _) =>
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
