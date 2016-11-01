package io.buoyant.router
package h2

import com.twitter.finagle.{Addr, Address, Name, Service}
import com.twitter.finagle.buoyant.h2
import com.twitter.finagle.transport.{TlsConfig, Transport}
import com.twitter.util._
import io.buoyant.test.FunSuite
import java.io.{File, FileInputStream, InputStream}
import java.nio.file.{Files, Paths}
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.{SSLContext, TrustManagerFactory}

class TlsEndToEndTest extends FunSuite {

  private[this] def loadResource(p: String): InputStream =
    getClass.getResourceAsStream(p)

  // Load a resource and write it as a temp file
  private[this] def loadPem(name: String): File = {
    val tmpDir = sys.props.getOrElse("java.io.tmpdir", "/tmp")
    val out = File.createTempFile(name, "pem")
    Files.copy(loadResource(s"/${name}.pem"), Paths.get(out.getPath), REPLACE_EXISTING)
    out
  }

  val service = Service.mk[h2.Request, h2.Response] { req =>
    Future.value(h2.Response(h2.Status.Ok, h2.Stream.Nil))
  }

  test("client/server works with TLS") {

    val srvCert = loadPem("linkerd-tls-e2e-cert")
    val srvKey = loadPem("linkerd-tls-e2e-key")
    val clientSslContext: SSLContext = {
      // Establish an SSL context that uses the provided caCert
      // Cribbed from http://stackoverflow.com/questions/18513792
      val cf = CertificateFactory.getInstance("X.509")
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      val ks = KeyStore.getInstance(KeyStore.getDefaultType)
      ks.load(null)
      ks.setCertificateEntry("caCert", cf.generateCertificate(loadResource("/ca.pem")))
      tmf.init(ks)
      val ctx = SSLContext.getInstance("TLS")
      ctx.init(null, tmf.getTrustManagers, null)
      ctx
    }

    val srvTls = TlsConfig.ServerCertAndKey(srvCert.getPath, srvKey.getPath, None, None, None)
    val srv = H2.server.configured(Transport.Tls(srvTls)).serve(":*", service)

    val srvAddr = Address(srv.boundAddress.asInstanceOf[InetSocketAddress])
    val srvName = Name.Bound(Var.value(Addr.Bound(srvAddr)), srvAddr)

    val clientTls = TlsConfig.ClientSslContextAndHostname(clientSslContext, "linkerd-tls-e2e")
    val client = H2.client.configured(Transport.Tls(clientTls)).newService(srvName, "")

    try {
      val rsp = await(client(h2.Request("http", h2.Method.Get, "auforiteh", "/a/parf", h2.Stream.Nil)))
      assert(rsp.status == h2.Status.Ok)

    } finally await(client.close().before(srv.close()))
  }
}
