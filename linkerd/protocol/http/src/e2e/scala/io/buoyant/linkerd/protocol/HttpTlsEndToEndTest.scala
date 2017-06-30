package io.buoyant.linkerd.protocol

import java.io.{File, InputStream}
import java.net.InetSocketAddress
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{Files, Paths}

import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientConfig
import com.twitter.finagle.http._
import com.twitter.finagle.{Http => FinagleHttp, Status => _, http => _, _}
import com.twitter.finagle.http.{param => _, _}
import com.twitter.finagle.http.{Method, Status}
import com.twitter.finagle.ssl.client.SslClientConfiguration
import com.twitter.finagle.ssl.server.SslServerConfiguration
import com.twitter.finagle.ssl.{ClientAuth, KeyCredentials, TrustCredentials}
import com.twitter.finagle.transport.Transport
import com.twitter.util._
import io.buoyant.config.Parser
import io.buoyant.test.FunSuite

class HttpTlsEndToEndTest extends FunSuite {

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
    Future.value(new Response.Ok)
  }

  test("client/server works with TLS") {
    val srv = {
      val srvCert = loadPem("linkerd-tls-e2e-cert")
      val srvKey = loadPem("linkerd-tls-e2e-key")
      FinagleHttp.server
        .configured(Transport.ServerSsl(Some(SslServerConfiguration(
          keyCredentials = KeyCredentials.CertAndKey(srvCert, srvKey)
        ))))
        .serve(":*", service)
    }

    val client = {
      val isa = srv.boundAddress.asInstanceOf[InetSocketAddress]
      val addr = Address(isa)
      val id = Path.read(s"/$$/inet/${isa.getAddress.getHostAddress}/${isa.getPort}")
      val srvName = Name.Bound(Var.value(Addr.Bound(addr)), id)

      val caCert = loadPem("cacert")

      val tls = Transport.ClientSsl(Some(SslClientConfiguration(
        hostname = Some("linkerd-tls-e2e"),
        trustCredentials = TrustCredentials.CertCollection(caCert)
      )))
      FinagleHttp.client
        .configured(tls)
        .newService(srvName, id.show)
    }

    val req = Request(Method.Get, "/a/parf")
    val rsp =
      try await(client(req))
      finally await(client.close().before(srv.close()))

    assert(rsp.status == Status.Ok)
  }
  // the following tests are ignored - the behavior they're testing has been
  // manually validated to work fine in a live linkerd, but does not seem to
  // work correctly in a test environment. it appears that using `Unspecified`
  // trust credentials does not work from inside of ScalaTest – the TLS client
  // can't seem to connect to a known certificate authority.
  //
  // TODO: i'd like to fix these tests eventually...
  //  - eliza, 06/30/2017
  ignore("TLS client with unspecified certs from config") {
    // https://github.com/linkerd/linkerd/issues/1436
    def parse(yaml: String): TlsClientConfig =
      Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)

    val srv = {
      val srvCert = loadPem("linkerd-tls-e2e-cert")
      val srvKey = loadPem("linkerd-tls-e2e-key")
      FinagleHttp.server
        .configured(Transport.ServerSsl(Some(SslServerConfiguration(
          keyCredentials = KeyCredentials.CertAndKey(srvCert, srvKey)
        ))))
        .serve(":*", service)
    }

    val client = {
      val isa = srv.boundAddress.asInstanceOf[InetSocketAddress]
      val addr = Address(isa)
      val id = Path.read(s"/$$/inet/${isa.getAddress.getHostAddress}/${isa.getPort}")
      val srvName = Name.Bound(Var.value(Addr.Bound(addr)), id)

      val caCert = loadPem("cacert")
      val yaml =s"""commonName: "$srvName""""
      val cfg = parse(yaml)
      FinagleHttp.client
        .configuredParams(cfg.params)
        .newService(srvName, id.show)
    }
    val req = Request(Method.Get, "/a/parf")
    val rsp =
      try await(client(req))
      finally await(client.close().before(srv.close()))

    assert(rsp.status == Status.Ok)


  }

  ignore("TLS client with unspecified certs") {
    // https://github.com/linkerd/linkerd/issues/1436
    def parse(yaml: String): TlsClientConfig =
      Parser.objectMapper(yaml, Nil).readValue[TlsClientConfig](yaml)

    val srv = {
      val srvCert = loadPem("linkerd-tls-e2e-cert")
      val srvKey = loadPem("linkerd-tls-e2e-key")
      FinagleHttp.server
        .configured(Transport.ServerSsl(Some(SslServerConfiguration(
          keyCredentials = KeyCredentials.CertAndKey(srvCert, srvKey),
          clientAuth = ClientAuth.Unspecified
        ))))
        .serve(":*", service)
    }

    val client = {
      val isa = srv.boundAddress.asInstanceOf[InetSocketAddress]
      val addr = Address(isa)
      val id = Path.read(s"/$$/inet/${isa.getAddress.getHostAddress}/${isa.getPort}")
      val srvName = Name.Bound(Var.value(Addr.Bound(addr)), id)

      val tls = Transport.ClientSsl(Some(SslClientConfiguration(
        hostname = Some(s"${isa.getAddress.getHostAddress}/${isa.getPort}"),
        trustCredentials = TrustCredentials.Unspecified
      )))
      FinagleHttp.client
        .configured(tls)
        .newService(srvName, id.show)
    }
    val req = Request(Method.Get, "/a/parf")
    val rsp =
      try await(client(req))
      finally await(client.close().before(srv.close()))

    assert(rsp.status == Status.Ok)


  }
}
