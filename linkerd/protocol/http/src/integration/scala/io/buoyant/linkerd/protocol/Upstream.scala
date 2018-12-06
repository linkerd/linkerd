package io.buoyant.linkerd.protocol

import com.twitter.finagle.http.TlsFilter
import com.twitter.finagle.ssl.KeyCredentials.Unspecified
import com.twitter.finagle.ssl.client.SslClientConfiguration
import com.twitter.finagle.ssl.{KeyCredentials, TrustCredentials}
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing.NullTracer
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.{Http => FinagleHttp, _}
import com.twitter.util.Var
import io.buoyant.linkerd.tls.TlsUtils.ServiceCert
import java.io.File
import java.net.InetSocketAddress

object Upstream {
  def mkTls(server: ListeningServer, tlsName: String, caCert: File, certAndKey: Option[ServiceCert] = None) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])
    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    FinagleHttp.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .configured(
        Transport.ClientSsl(Some(SslClientConfiguration(
          hostname = Some(tlsName),
          keyCredentials = certAndKey.map(c => KeyCredentials.CertAndKey(c.cert, c.key)).getOrElse(Unspecified),
          trustCredentials = TrustCredentials.CertCollection(caCert)
        )))
      )
      .withStack(_.remove(TlsFilter.role)) // do NOT rewrite Host headers using tlsName
      .newClient(name, "upstream").toService
  }

  def mk(server: ListeningServer) = {
    val address = Address(server.boundAddress.asInstanceOf[InetSocketAddress])

    val name = Name.Bound(Var.value(Addr.Bound(address)), address)
    FinagleHttp.client
      .configured(param.Stats(NullStatsReceiver))
      .configured(param.Tracer(NullTracer))
      .withStack(_.remove(TlsFilter.role)) // do NOT rewrite Host headers using tlsName
      .newClient(name, "upstream").toService
  }
}
