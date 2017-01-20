package com.twitter.finagle.buoyant
package h2

import com.twitter.finagle.{Addr, Address, Name, Path, Service}
import com.twitter.finagle.transport.{TlsConfig, Transport}
import com.twitter.util._
import io.buoyant.test.FunSuite
import java.io.{File, FileInputStream, InputStream}
import java.nio.file.{Files, Paths}
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.net.InetSocketAddress
import java.security.cert.{CertificateFactory, X509Certificate}

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

  val service = Service.mk[Request, Response] { req =>
    Future.value(Response(Status.Ok, Stream.empty()))
  }

  test("client/server works with TLS") {
    val srv = {
      val srvCert = loadPem("linkerd-tls-e2e-cert")
      val srvKey = loadPem("linkerd-tls-e2e-key")
      H2.server
        .configured(Transport.Tls(TlsConfig.ServerCertAndKey(srvCert.getPath, srvKey.getPath, None, None, None)))
        .serve(":*", service)
    }

    val client = {
      val isa = srv.boundAddress.asInstanceOf[InetSocketAddress]
      val addr = Address(isa)
      val id = Path.read(s"/$$/inet/${isa.getAddress.getHostAddress}/${isa.getPort}")
      val srvName = Name.Bound(Var.value(Addr.Bound(addr)), id)

      val caCert = CertificateFactory.getInstance("X.509")
        .generateCertificate(loadResource("/ca.pem"))
        .asInstanceOf[X509Certificate]

      val security = TlsClientPrep.TransportSecurity.Secure()
      val trust = TlsClientPrep.Trust.Verified("linkerd-tls-e2e", Seq(caCert))
      H2.client
        .configured(TlsClientPrep.TransportSecurity(security))
        .configured(TlsClientPrep.Trust(trust))
        .newService(srvName, id.show)
    }

    val req = Request("http", Method.Get, "auforiteh", "/a/parf", Stream.empty())
    val rsp =
      try await(client(req))
      finally await(client.close().before(srv.close()))

    assert(rsp.status == Status.Ok)
  }
}
