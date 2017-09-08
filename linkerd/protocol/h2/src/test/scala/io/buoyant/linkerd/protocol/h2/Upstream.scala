package io.buoyant.linkerd.protocol.h2

import com.twitter.finagle.buoyant.H2
import com.twitter.finagle.ssl.KeyCredentials.Unspecified
import com.twitter.finagle.ssl.client.SslClientConfiguration
import com.twitter.finagle.ssl.{KeyCredentials, TrustCredentials}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Addr, Address, ListeningServer, Name, param => fparam}
import com.twitter.util.Var
import io.buoyant.linkerd.tls.TlsUtils.ServiceCert
import java.io.File
import java.net.InetSocketAddress

object Upstream {
  def mk(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    H2.client
      .configured(fparam.Stats(NullStatsReceiver))
      .configured(fparam.Tracer(NullTracer))
      .newClient(name, "upstream").toService
  }

  def mkTls(server: ListeningServer, tlsName: String, caCert: File, certAndKey: Option[ServiceCert] = None) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    H2.client
      .configured(fparam.Stats(NullStatsReceiver))
      .configured(fparam.Tracer(NullTracer))
      .configured(
        Transport.ClientSsl(Some(SslClientConfiguration(
          hostname = Some(tlsName),
          keyCredentials = certAndKey.map(c => KeyCredentials.CertAndKey(c.cert, c.key)).getOrElse(Unspecified),
          trustCredentials = TrustCredentials.CertCollection(caCert)
        )))
      )
      .newClient(name, "upstream").toService
  }
}
